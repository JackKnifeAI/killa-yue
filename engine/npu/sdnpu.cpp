// sdnpu: Stable Diffusion 1.5 on the Hexagon NPU through QNN context binaries.
//
// Loads Qualcomm's three precompiled graphs (text encoder, UNet, VAE decoder), runs a
// DDIM sampling loop with classifier-free guidance, and writes a 512x512 RGB image.
// Token ids come from the caller, so this program has no tokenizer.
//
//   sdnpu --dir <folder with text_encoder.bin unet.bin vae.bin> --lib <folder with libQnn*.so>
//         --tokens 49406,...  --neg 49406,...  [--steps 20] [--guidance 7.5] [--seed 1] --out image.rgb
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <string>
#include <vector>

#include "QnnInterface.h"
#include "System/QnnSystemInterface.h"
#include "HTP/QnnHtpDevice.h"
#include "HTP/QnnHtpPerfInfrastructure.h"

using Clock = std::chrono::steady_clock;
static double ms_since(Clock::time_point t) {
  return std::chrono::duration<double, std::milli>(Clock::now() - t).count();
}

static QNN_INTERFACE_VER_TYPE qnn;
static QNN_SYSTEM_INTERFACE_VER_TYPE qsys;
static Qnn_LogHandle_t g_log = nullptr;
static Qnn_BackendHandle_t g_backend = nullptr;
static Qnn_DeviceHandle_t g_device = nullptr;

static void log_cb(const char* fmt, QnnLog_Level_t level, uint64_t, va_list args) {
  if (level > QNN_LOG_LEVEL_WARN) return;
  fprintf(stderr, "[QNN] ");
  vfprintf(stderr, fmt, args);
  fprintf(stderr, "\n");
}

#define CHECK(call, what)                                              \
  do {                                                                 \
    Qnn_ErrorHandle_t e_ = (call);                                     \
    if (e_ != QNN_SUCCESS) {                                           \
      fprintf(stderr, "FATAL: %s failed, code %llu\n", what,           \
              (unsigned long long) QNN_GET_ERROR_CODE(e_));            \
      exit(2);                                                         \
    }                                                                  \
  } while (0)

// One graph loaded from a context binary, with buffers for its inputs and outputs.
struct Graph {
  std::string file;
  void* map = nullptr;
  size_t size = 0;
  Qnn_ContextHandle_t ctx = nullptr;
  Qnn_GraphHandle_t graph = nullptr;
  std::vector<Qnn_Tensor_t> in, out;
  std::vector<std::vector<uint8_t>> inbuf, outbuf;

  // V2 tensors extend V1, so the shared fields are read through v1 for both.
  static Qnn_TensorV1_t& t1(Qnn_Tensor_t& t) { return t.v1; }

  static size_t elements(Qnn_Tensor_t& t) {
    size_t n = 1;
    for (uint32_t i = 0; i < t1(t).rank; i++) n *= t1(t).dimensions[i];
    return n;
  }
  static size_t bytes_per(Qnn_DataType_t d) {
    switch (d) {
      case QNN_DATATYPE_UFIXED_POINT_8: case QNN_DATATYPE_SFIXED_POINT_8:
      case QNN_DATATYPE_UINT_8: case QNN_DATATYPE_INT_8: return 1;
      case QNN_DATATYPE_UFIXED_POINT_16: case QNN_DATATYPE_SFIXED_POINT_16:
      case QNN_DATATYPE_UINT_16: case QNN_DATATYPE_INT_16: case QNN_DATATYPE_FLOAT_16: return 2;
      default: return 4;
    }
  }

  void load(const std::string& path) {
    file = path;
    auto t0 = Clock::now();
    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) { fprintf(stderr, "FATAL: cannot open %s\n", path.c_str()); exit(2); }
    struct stat st;
    fstat(fd, &st);
    size = st.st_size;
    map = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) { fprintf(stderr, "FATAL: cannot map %s\n", path.c_str()); exit(2); }

    QnnSystemContext_Handle_t sc = nullptr;
    CHECK(qsys.systemContextCreate(&sc), "systemContextCreate");
    const QnnSystemContext_BinaryInfo_t* info = nullptr;
    Qnn_ContextBinarySize_t infoSize = 0;
    CHECK(qsys.systemContextGetBinaryInfo(sc, map, size, &info, &infoSize), "systemContextGetBinaryInfo");
    QnnSystemContext_GraphInfo_t* graphs = nullptr;
    uint32_t n = 0;
    if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1) { graphs = info->contextBinaryInfoV1.graphs; n = info->contextBinaryInfoV1.numGraphs; }
    else if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2) { graphs = info->contextBinaryInfoV2.graphs; n = info->contextBinaryInfoV2.numGraphs; }
    else { graphs = info->contextBinaryInfoV3.graphs; n = info->contextBinaryInfoV3.numGraphs; }
    if (n < 1) { fprintf(stderr, "FATAL: %s holds no graph\n", path.c_str()); exit(2); }
    const char* name; Qnn_Tensor_t* gi; Qnn_Tensor_t* go; uint32_t ni, no;
    QnnSystemContext_GraphInfo_t& g = graphs[0];
    if (g.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1) { name = g.graphInfoV1.graphName; gi = g.graphInfoV1.graphInputs; ni = g.graphInfoV1.numGraphInputs; go = g.graphInfoV1.graphOutputs; no = g.graphInfoV1.numGraphOutputs; }
    else if (g.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2) { name = g.graphInfoV2.graphName; gi = g.graphInfoV2.graphInputs; ni = g.graphInfoV2.numGraphInputs; go = g.graphInfoV2.graphOutputs; no = g.graphInfoV2.numGraphOutputs; }
    else { name = g.graphInfoV3.graphName; gi = g.graphInfoV3.graphInputs; ni = g.graphInfoV3.numGraphInputs; go = g.graphInfoV3.graphOutputs; no = g.graphInfoV3.numGraphOutputs; }

    // Copy the tensor descriptions (names and shapes point into the system context, which stays alive).
    in.assign(gi, gi + ni);
    out.assign(go, go + no);
    inbuf.resize(ni);
    outbuf.resize(no);
    for (uint32_t i = 0; i < ni; i++) attach(in[i], inbuf[i]);
    for (uint32_t i = 0; i < no; i++) attach(out[i], outbuf[i]);

    CHECK(qnn.contextCreateFromBinary(g_backend, g_device, nullptr, map, size, &ctx, nullptr), "contextCreateFromBinary");
    CHECK(qnn.graphRetrieve(ctx, name, &graph), "graphRetrieve");
    fprintf(stderr, "[load] %s: graph \"%s\", %.0f ms\n", path.c_str(), name, ms_since(t0));
    for (auto& t : in) describe("in ", t);
    for (auto& t : out) describe("out", t);
  }

  static void attach(Qnn_Tensor_t& t, std::vector<uint8_t>& buf) {
    buf.assign(elements(t) * bytes_per(t1(t).dataType), 0);
    t1(t).memType = QNN_TENSORMEMTYPE_RAW;
    t1(t).clientBuf.data = buf.data();
    t1(t).clientBuf.dataSize = (uint32_t) buf.size();
  }

  static void describe(const char* tag, Qnn_Tensor_t& t) {
    fprintf(stderr, "  %s %-16s [", tag, t1(t).name);
    for (uint32_t i = 0; i < t1(t).rank; i++) fprintf(stderr, "%s%u", i ? "," : "", t1(t).dimensions[i]);
    fprintf(stderr, "] type 0x%x scale %g offset %d\n", (unsigned) t1(t).dataType,
            t1(t).quantizeParams.scaleOffsetEncoding.scale, t1(t).quantizeParams.scaleOffsetEncoding.offset);
  }

  int find(std::vector<Qnn_Tensor_t>& v, const char* name) {
    for (size_t i = 0; i < v.size(); i++) if (strstr(t1(v[i]).name, name)) return (int) i;
    fprintf(stderr, "FATAL: %s has no tensor named like \"%s\"\n", file.c_str(), name);
    exit(2);
  }

  // real = (stored + offset) * scale
  void put(int idx, const float* x) {
    Qnn_Tensor_t& t = in[idx];
    size_t n = elements(t);
    float scale = t1(t).quantizeParams.scaleOffsetEncoding.scale;
    int32_t off = t1(t).quantizeParams.scaleOffsetEncoding.offset;
    if (t1(t).dataType == QNN_DATATYPE_UFIXED_POINT_16) {
      uint16_t* q = (uint16_t*) inbuf[idx].data();
      for (size_t i = 0; i < n; i++) {
        float v = std::round(x[i] / scale) - off;
        q[i] = (uint16_t) std::fmin(65535.f, std::fmax(0.f, v));
      }
    } else if (t1(t).dataType == QNN_DATATYPE_FLOAT_32) {
      memcpy(inbuf[idx].data(), x, n * 4);
    } else {
      fprintf(stderr, "FATAL: unsupported input type 0x%x\n", (unsigned) t1(t).dataType);
      exit(2);
    }
  }
  void put_ints(int idx, const std::vector<int32_t>& v) {
    if (elements(in[idx]) != v.size() || bytes_per(t1(in[idx]).dataType) != 4) {
      fprintf(stderr, "FATAL: token tensor is not %zu int32 values\n", v.size());
      exit(2);
    }
    memcpy(inbuf[idx].data(), v.data(), v.size() * 4);
  }
  void get(int idx, std::vector<float>& x) {
    Qnn_Tensor_t& t = out[idx];
    size_t n = elements(t);
    x.resize(n);
    float scale = t1(t).quantizeParams.scaleOffsetEncoding.scale;
    int32_t off = t1(t).quantizeParams.scaleOffsetEncoding.offset;
    if (t1(t).dataType == QNN_DATATYPE_UFIXED_POINT_16) {
      const uint16_t* q = (const uint16_t*) outbuf[idx].data();
      for (size_t i = 0; i < n; i++) x[i] = ((int32_t) q[i] + off) * scale;
    } else if (t1(t).dataType == QNN_DATATYPE_FLOAT_32) {
      memcpy(x.data(), outbuf[idx].data(), n * 4);
    } else {
      fprintf(stderr, "FATAL: unsupported output type 0x%x\n", (unsigned) t1(t).dataType);
      exit(2);
    }
  }
  double run() {
    auto t0 = Clock::now();
    CHECK(qnn.graphExecute(graph, in.data(), (uint32_t) in.size(), out.data(), (uint32_t) out.size(), nullptr, nullptr), "graphExecute");
    return ms_since(t0);
  }
  void unload() {
    if (ctx) qnn.contextFree(ctx, nullptr);
    ctx = nullptr;
    if (map) munmap(map, size);
    map = nullptr;
  }
};

// Ask the NPU for its fastest clocks while we work; without this it idles between graphs.
static void burst_mode() {
  QnnDevice_Infrastructure_t infra = nullptr;
  if (qnn.deviceGetInfrastructure(&infra) != QNN_SUCCESS || !infra) return;
  QnnHtpDevice_Infrastructure_t* htp = (QnnHtpDevice_Infrastructure_t*) infra;
  if (htp->infraType != QNN_HTP_DEVICE_INFRASTRUCTURE_TYPE_PERF) return;
  QnnHtpDevice_PerfInfrastructure_t perf = htp->perfInfra;
  uint32_t id = 0;
  if (perf.createPowerConfigId(0, 0, &id) != QNN_SUCCESS) return;
  QnnHtpPerfInfrastructure_PowerConfig_t dcvs;
  memset(&dcvs, 0, sizeof(dcvs));
  dcvs.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
  dcvs.dcvsV3Config.contextId = id;
  dcvs.dcvsV3Config.dcvsEnable = 0;
  dcvs.dcvsV3Config.setDcvsEnable = 1;
  dcvs.dcvsV3Config.powerMode = QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
  dcvs.dcvsV3Config.setSleepLatency = 1;
  dcvs.dcvsV3Config.sleepLatency = 40;
  dcvs.dcvsV3Config.setBusParams = 1;
  dcvs.dcvsV3Config.busVoltageCornerMin = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
  dcvs.dcvsV3Config.busVoltageCornerTarget = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
  dcvs.dcvsV3Config.busVoltageCornerMax = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
  dcvs.dcvsV3Config.setCoreParams = 1;
  dcvs.dcvsV3Config.coreVoltageCornerMin = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
  dcvs.dcvsV3Config.coreVoltageCornerTarget = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
  dcvs.dcvsV3Config.coreVoltageCornerMax = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
  const QnnHtpPerfInfrastructure_PowerConfig_t* list[] = {&dcvs, nullptr};
  if (perf.setPowerConfig(id, list) == QNN_SUCCESS) fprintf(stderr, "[npu] burst power mode set\n");
}

static std::vector<int32_t> ids(const char* s) {
  std::vector<int32_t> v;
  while (*s) {
    v.push_back((int32_t) strtol(s, (char**) &s, 10));
    if (*s == ',') s++;
  }
  v.resize(77, 49407);
  return v;
}

int main(int argc, char** argv) {
  std::string dir = ".", lib = ".", out = "image.rgb", tokens, neg;
  int steps = 20;
  float guidance = 7.5f;
  unsigned seed = 1;
  for (int i = 1; i + 1 < argc; i += 2) {
    std::string k = argv[i], v = argv[i + 1];
    if (k == "--dir") dir = v; else if (k == "--lib") lib = v; else if (k == "--out") out = v;
    else if (k == "--tokens") tokens = v; else if (k == "--neg") neg = v;
    else if (k == "--steps") steps = atoi(v.c_str()); else if (k == "--guidance") guidance = atof(v.c_str());
    else if (k == "--seed") seed = (unsigned) strtoul(v.c_str(), nullptr, 10);
  }
  if (tokens.empty()) { fprintf(stderr, "usage: sdnpu --dir D --lib L --tokens ids --neg ids --out file.rgb\n"); return 1; }
  auto total = Clock::now();

  void* hb = dlopen((lib + "/libQnnHtp.so").c_str(), RTLD_NOW | RTLD_GLOBAL);
  void* hs = dlopen((lib + "/libQnnSystem.so").c_str(), RTLD_NOW | RTLD_GLOBAL);
  if (!hb || !hs) { fprintf(stderr, "FATAL: cannot load the QNN libraries: %s\n", dlerror()); return 2; }
  typedef Qnn_ErrorHandle_t (*Providers)(const QnnInterface_t***, uint32_t*);
  typedef Qnn_ErrorHandle_t (*SysProviders)(const QnnSystemInterface_t***, uint32_t*);
  const QnnInterface_t** pv = nullptr; uint32_t np = 0;
  const QnnSystemInterface_t** sv = nullptr; uint32_t ns = 0;
  CHECK(((Providers) dlsym(hb, "QnnInterface_getProviders"))(&pv, &np), "QnnInterface_getProviders");
  CHECK(((SysProviders) dlsym(hs, "QnnSystemInterface_getProviders"))(&sv, &ns), "QnnSystemInterface_getProviders");
  qnn = pv[0]->QNN_INTERFACE_VER_NAME;
  qsys = sv[0]->QNN_SYSTEM_INTERFACE_VER_NAME;
  fprintf(stderr, "[npu] backend API %u.%u.%u\n", pv[0]->apiVersion.coreApiVersion.major,
          pv[0]->apiVersion.coreApiVersion.minor, pv[0]->apiVersion.coreApiVersion.patch);

  CHECK(qnn.logCreate(log_cb, QNN_LOG_LEVEL_WARN, &g_log), "logCreate");
  CHECK(qnn.backendCreate(g_log, nullptr, &g_backend), "backendCreate");
  CHECK(qnn.deviceCreate(g_log, nullptr, &g_device), "deviceCreate");
  burst_mode();

  // 1. text encoder, once for the prompt and once for the empty prompt
  Graph te;
  te.load(dir + "/text_encoder.bin");
  std::vector<float> cond, uncond;
  te.put_ints(0, ids(tokens.c_str()));
  double t_te = te.run();
  te.get(0, cond);
  te.put_ints(0, ids(neg.c_str()));
  t_te += te.run();
  te.get(0, uncond);
  te.unload();

  // 2. UNet, DDIM sampling
  Graph un;
  un.load(dir + "/unet.bin");
  int iLat = un.find(un.in, "latent"), iT = un.find(un.in, "timestep"), iTxt = un.find(un.in, "text");
  const int N = 64 * 64 * 4, TRAIN = 1000;
  std::vector<double> ac(TRAIN);
  {
    double b0 = std::sqrt(0.00085), b1 = std::sqrt(0.012), prod = 1.0;
    for (int i = 0; i < TRAIN; i++) {
      double b = b0 + (b1 - b0) * i / (TRAIN - 1);
      prod *= 1.0 - b * b;
      ac[i] = prod;
    }
  }
  std::vector<float> x(N), eps_c, eps_u;
  std::mt19937 rng(seed);
  std::normal_distribution<float> gauss(0.f, 1.f);
  for (int i = 0; i < N; i++) x[i] = gauss(rng);
  double t_unet = 0;
  int stride = TRAIN / steps;
  for (int s = 0; s < steps; s++) {
    int t = (steps - 1 - s) * stride + 1;
    int tp = t - stride;
    float tf = (float) t;
    un.put(iLat, x.data());
    un.put(iT, &tf);
    un.put(iTxt, cond.data());
    t_unet += un.run();
    un.get(0, eps_c);
    un.put(iTxt, uncond.data());
    t_unet += un.run();
    un.get(0, eps_u);
    double a = ac[t], ap = tp >= 0 ? ac[tp] : ac[0];
    double sa = std::sqrt(a), s1 = std::sqrt(1 - a), sap = std::sqrt(ap), s1p = std::sqrt(1 - ap);
    for (int i = 0; i < N; i++) {
      double e = eps_u[i] + guidance * (eps_c[i] - eps_u[i]);
      double x0 = (x[i] - s1 * e) / sa;
      x[i] = (float) (sap * x0 + s1p * e);
    }
    fprintf(stderr, "[unet] step %d/%d\n", s + 1, steps);
  }
  un.unload();

  // 3. VAE decoder
  Graph va;
  va.load(dir + "/vae.bin");
  // Qualcomm's decoder takes the UNet's latent as it is; the usual 1/0.18215 scaling is inside the graph
  // (its input range is about -11.7 to 10.6, the range of unscaled-by-us latents).
  va.put(0, x.data());
  double t_vae = va.run();
  std::vector<float> img;
  va.get(0, img);
  va.unload();

  float lo = 1e9f, hi = -1e9f;
  for (float v : img) { lo = std::fmin(lo, v); hi = std::fmax(hi, v); }
  std::vector<uint8_t> rgb(img.size());
  for (size_t i = 0; i < img.size(); i++) rgb[i] = (uint8_t) std::fmin(255.f, std::fmax(0.f, img[i] * 255.f + 0.5f));
  FILE* f = fopen(out.c_str(), "wb");
  if (!f) { fprintf(stderr, "FATAL: cannot write %s\n", out.c_str()); return 2; }
  fwrite(rgb.data(), 1, rgb.size(), f);
  fclose(f);
  fprintf(stderr, "[done] text %.0f ms, unet %.0f ms (%.1f ms per call, %d calls), vae %.0f ms, image range %.3f..%.3f\n",
          t_te, t_unet, t_unet / (steps * 2), steps * 2, t_vae, lo, hi);
  fprintf(stderr, "[done] total %.1f s including loading -> %s (%zu bytes, 512x512 RGB)\n", ms_since(total) / 1000.0, out.c_str(), rgb.size());
  return 0;
}

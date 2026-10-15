// Demucs on ExecuTorch (Vulkan): one window through the exported htdemucs program, then the mask and inverse
// STFT on the CPU (stem_istft.hpp). Android only: it links the ExecuTorch runtime, downloaded with the model.
#include "studio/et_model.h"
#include <cmath>
#include <cstring>
#include "executorch_ffi.h"
#include "stem_istft.hpp"

namespace studio {

namespace {

bool ok(ETStatus* s, std::string& err) {
    if (!s) {
        err = "ExecuTorch returned no status";
        return false;
    }
    const bool good = s->code == ET_OK;
    if (!good) err = s->message ? s->message : "ExecuTorch failed";
    et_status_free(s);
    return good;
}

}  // namespace

EtStemModel::~EtStemModel() {
    if (module_) et_module_free(static_cast<ETModule*>(module_));
}

bool EtStemModel::vulkan() { return et_backend_available(ET_BACKEND_VULKAN) != 0; }

bool EtStemModel::load(const char* path, std::string& err) {
    ETModule* m = nullptr;
    if (!ok(et_module_load_file(path, &m), err)) return false;
    if (et_module_input_count(m) != 1 || et_module_output_count(m) != 2) {
        et_module_free(m);
        err = "this is not the Demucs model the Studio expects";
        return false;
    }
    module_ = m;
    return true;
}

bool EtStemModel::run(const float* in, float* out, std::string& err) {
    const int S = killa_stems::frames;
    int64_t shape[] = {1, 2, S};
    ETTensor* t = nullptr;
    if (!ok(et_tensor_create(const_cast<float*>(in), (size_t)2 * S * sizeof(float), shape, 3, ET_DTYPE_FLOAT32, &t), err)) return false;
    ETTensor** outs = nullptr;
    int32_t count = 0;
    const bool ran = ok(et_module_forward(static_cast<ETModule*>(module_), &t, 1, &outs, &count), err);
    et_tensor_free(t);
    if (!ran) return false;
    bool good = count == 2 && et_tensor_dtype(outs[0]) == ET_DTYPE_FLOAT32 && et_tensor_dtype(outs[1]) == ET_DTYPE_FLOAT32;
    const int T = (S + killa_stems::hop - 1) / killa_stems::hop;
    if (good) good = et_tensor_data_size(outs[0]) == (size_t)16 * 2048 * T * sizeof(float) && et_tensor_data_size(outs[1]) == (size_t)8 * S * sizeof(float);
    if (!good) {
        err = "Demucs gave an answer of the wrong shape";
    } else {
        try {
            std::vector<float> sep = killa_stems::reconstruct((const float*)et_tensor_data(outs[0]), (const float*)et_tensor_data(outs[1]), T);
            std::memcpy(out, sep.data(), sep.size() * sizeof(float));
        } catch (const std::exception& x) {
            err = x.what();
            good = false;
        }
    }
    et_tensor_array_free(outs, count);
    return good;
}

}  // namespace studio

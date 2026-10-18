// Local, read-only capability report. No network, serial, account, app list or device UUID.
// Baseline armv8-a: the probe itself must work before optional CPU ISA is selected.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <android/api-level.h>
#include <asm/hwcap.h>
#include <sys/auxv.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <dlfcn.h>
#include <cstdio>
#include <cstring>
#include <algorithm>
#include <string>
#include <vector>

static std::string quote(const char * s) {
    std::string out="\"";
    for (const unsigned char * p=(const unsigned char *)s; *p; ++p) {
        if (*p=='"' || *p=='\\') {out+='\\'; out+=(char)*p;}
        else if (*p<32) {char escaped[7]; snprintf(escaped,sizeof escaped,"\\u%04x",*p);out+=escaped;}
        else out+=(char)*p;
    }
    return out+'"';
}
static std::string property(const char * name) {
    char value[PROP_VALUE_MAX]={0}; __system_property_get(name,value);return quote(value);
}
static long long memory(const char * key) {
    FILE * f=fopen("/proc/meminfo","r"); if(!f)return -1;
    char line[256];long long result=-1;
    while(fgets(line,sizeof line,f)) if(strncmp(line,key,strlen(key))==0) {sscanf(line+strlen(key),"%lld",&result);break;}
    fclose(f);return result;
}
static const char * boolean(bool b) {return b?"true":"false";}
int main(int argc,char **argv) {
    if(argc>2){fprintf(stderr,"Usage: android-device-probe [Vulkan-loader-path]\n");return 2;}
    const char * path=argc==2?argv[1]:"/system/lib64/libvulkan.so";
    unsigned long caps=getauxval(AT_HWCAP);
    printf("{\"schema\":1,\"model\":%s,\"manufacturer\":%s,\"soc\":%s,\"api\":%d,\"page_bytes\":%ld,",
        property("ro.product.model").c_str(),property("ro.product.manufacturer").c_str(),property("ro.soc.model").c_str(),
        android_get_device_api_level(),sysconf(_SC_PAGESIZE));
    printf("\"memory_total_kib\":%lld,\"memory_available_kib\":%lld,\"cpu\":{\"abi\":\"arm64-v8a\",\"online_cores\":%ld,\"dotprod\":%s,\"fp16_vector\":%s},",
        memory("MemTotal:"),memory("MemAvailable:"),sysconf(_SC_NPROCESSORS_ONLN),boolean(caps&HWCAP_ASIMDDP),boolean(caps&HWCAP_ASIMDHP));
    printf("\"vulkan_loader\":%s,\"devices\":[",quote(path).c_str());
    void * library=dlopen(path,RTLD_NOW|RTLD_LOCAL);
    if(!library){printf("],\"vulkan_error\":%s}\n",quote(dlerror()).c_str());return 0;}
    auto get=(PFN_vkGetInstanceProcAddr)dlsym(library,"vkGetInstanceProcAddr");
    if(!get){printf("],\"vulkan_error\":\"missing vkGetInstanceProcAddr\"}\n");dlclose(library);return 0;}
    auto enumerateVersion=(PFN_vkEnumerateInstanceVersion)get(VK_NULL_HANDLE,"vkEnumerateInstanceVersion");
    uint32_t version=VK_API_VERSION_1_0;
    if(enumerateVersion && enumerateVersion(&version)!=VK_SUCCESS)version=VK_API_VERSION_1_0;
    VkApplicationInfo app={VK_STRUCTURE_TYPE_APPLICATION_INFO};app.pApplicationName="Killa local capability probe";
    app.apiVersion=std::min(version,VK_API_VERSION_1_1);
    VkInstanceCreateInfo createInfo={VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};createInfo.pApplicationInfo=&app;
    auto create=(PFN_vkCreateInstance)get(VK_NULL_HANDLE,"vkCreateInstance");
    VkInstance instance=VK_NULL_HANDLE;
    VkResult result=create?create(&createInfo,nullptr,&instance):VK_ERROR_INITIALIZATION_FAILED;
    if(result!=VK_SUCCESS){printf("],\"vulkan_error\":\"vkCreateInstance: %d\"}\n",result);dlclose(library);return 0;}
#define FN(name) auto name=(PFN_##name)get(instance,#name)
    FN(vkDestroyInstance);FN(vkEnumeratePhysicalDevices);FN(vkGetPhysicalDeviceProperties);
    FN(vkGetPhysicalDeviceFeatures);FN(vkGetPhysicalDeviceQueueFamilyProperties);
    FN(vkGetPhysicalDeviceMemoryProperties);FN(vkEnumerateDeviceExtensionProperties);
    FN(vkGetPhysicalDeviceFeatures2);
    if(!vkDestroyInstance||!vkEnumeratePhysicalDevices||!vkGetPhysicalDeviceProperties||!vkGetPhysicalDeviceFeatures||!vkGetPhysicalDeviceQueueFamilyProperties||!vkGetPhysicalDeviceMemoryProperties||!vkEnumerateDeviceExtensionProperties){
        printf("],\"vulkan_error\":\"incomplete instance entry points\"}\n");
        if(vkDestroyInstance)vkDestroyInstance(instance,nullptr);dlclose(library);return 0;
    }
    uint32_t count=0;
    result=vkEnumeratePhysicalDevices(instance,&count,nullptr);
    std::vector<VkPhysicalDevice> devices(count);
    if(result==VK_SUCCESS&&count)result=vkEnumeratePhysicalDevices(instance,&count,devices.data());
    if(result!=VK_SUCCESS){printf("],\"vulkan_error\":\"device enumeration: %d\"}\n",result);vkDestroyInstance(instance,nullptr);dlclose(library);return 0;}
    devices.resize(count);
    for(size_t i=0;i<devices.size();i++) {
        auto device=devices[i];VkPhysicalDeviceProperties p;vkGetPhysicalDeviceProperties(device,&p);
        VkPhysicalDeviceFeatures f;vkGetPhysicalDeviceFeatures(device,&f);
        uint32_t qcount=0;vkGetPhysicalDeviceQueueFamilyProperties(device,&qcount,nullptr);
        std::vector<VkQueueFamilyProperties> queues(qcount);vkGetPhysicalDeviceQueueFamilyProperties(device,&qcount,queues.data());
        bool compute=false;for(auto &q:queues)compute|=(q.queueCount>0&&(q.queueFlags&VK_QUEUE_COMPUTE_BIT));
        uint32_t n=0;vkEnumerateDeviceExtensionProperties(device,nullptr,&n,nullptr);
        std::vector<VkExtensionProperties> extensions(n);vkEnumerateDeviceExtensionProperties(device,nullptr,&n,extensions.data());
        auto has=[&](const char * name){return std::any_of(extensions.begin(),extensions.end(),[&](const auto &e){return strcmp(name,e.extensionName)==0;});};
        bool can16=p.apiVersion>=VK_API_VERSION_1_1||has("VK_KHR_16bit_storage");
        bool canFloat=p.apiVersion>=VK_API_VERSION_1_2||has("VK_KHR_shader_float16_int8");
        VkPhysicalDeviceFeatures2 f2={VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
        VkPhysicalDevice16BitStorageFeatures storage={VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES};
        VkPhysicalDeviceShaderFloat16Int8Features arithmetic={VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES};
        if(can16){storage.pNext=f2.pNext;f2.pNext=&storage;}
        if(canFloat){arithmetic.pNext=f2.pNext;f2.pNext=&arithmetic;}
        bool queried=vkGetPhysicalDeviceFeatures2&&app.apiVersion>=VK_API_VERSION_1_1;
        if(queried)vkGetPhysicalDeviceFeatures2(device,&f2);
        if(i)printf(",");
        printf("{\"name\":%s,\"vendor_id\":%u,\"device_id\":%u,\"api_version\":%u,\"driver_version\":%u,\"compute_queue\":%s,\"max_storage_buffer_bytes\":%u,\"max_allocations\":%u,\"max_workgroup_invocations\":%u,\"shader_int16\":%s,\"extended_features_queried\":%s,\"storage_buffer_16bit\":%s,\"shader_float16\":%s,\"memory_heaps\":[",
            quote(p.deviceName).c_str(),p.vendorID,p.deviceID,p.apiVersion,p.driverVersion,boolean(compute),p.limits.maxStorageBufferRange,
            p.limits.maxMemoryAllocationCount,p.limits.maxComputeWorkGroupInvocations,boolean(f.shaderInt16),boolean(queried),boolean(storage.storageBuffer16BitAccess),boolean(arithmetic.shaderFloat16));
        VkPhysicalDeviceMemoryProperties mem;vkGetPhysicalDeviceMemoryProperties(device,&mem);
        for(uint32_t j=0;j<mem.memoryHeapCount;j++)printf("%s{\"size_bytes\":%llu,\"device_local\":%s}",j?",":"",(unsigned long long)mem.memoryHeaps[j].size,boolean(mem.memoryHeaps[j].flags&VK_MEMORY_HEAP_DEVICE_LOCAL_BIT));
        printf("]}");
    }
    printf("],\"note\":\"Capability discovery only; no compute correctness or performance claim. Heap sizes are not currently available RAM.\"}\n");
    vkDestroyInstance(instance,nullptr);dlclose(library);return 0;
}

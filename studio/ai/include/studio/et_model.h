// Demucs (htdemucs) exported for ExecuTorch, as the Studio's StemModel. Android only.
#pragma once
#include <string>
#include "studio/stems.h"

namespace studio {

class EtStemModel : public StemModel {
public:
    ~EtStemModel() override;
    static bool vulkan();  // the runtime was built with the Vulkan (GPU) backend
    bool load(const char* path, std::string& err);
    bool run(const float* in, float* out, std::string& err) override;

private:
    void* module_ = nullptr;
};

}  // namespace studio

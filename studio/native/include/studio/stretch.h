#pragma once
#include <string>
namespace studio {
bool stretchFile(const char* input, const char* output, long offset, long frames, double ratio, double semitones, std::string& error);
}

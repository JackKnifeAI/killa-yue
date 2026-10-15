// Renders a session off-line, through the same Engine as playback, to a float WAV.
#pragma once
#include <string>
#include "studio/engine.h"

namespace studio {

// Plays the engine from frame 0 as fast as it can with its sources off-line (reads wait for the disk, so
// nothing is missing), skips the limiter's delay so the file lines up with the song, and writes exactly
// `frames` frames. Not for use while a device is calling process().
bool exportMix(Engine& e, long frames, const char* path, std::string& error);

}  // namespace studio

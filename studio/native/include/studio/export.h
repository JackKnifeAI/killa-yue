// Renders a session off-line, through the same Engine as playback, to a float WAV.
#pragma once
#include <atomic>
#include <string>
#include "studio/engine.h"

namespace studio {

// Plays the engine from frame 0 as fast as it can with its sources off-line (reads wait for the disk, so
// nothing is missing), skips the limiter's delay so the file lines up with the song, and writes exactly
// `frames` frames. Not for use while a device is calling process().
// Watching and stopping an export from another thread.
struct ExportControl {
    std::atomic<long>* done = nullptr;    // frames written so far (only goes up)
    std::atomic<bool>* cancel = nullptr;  // set it to stop: the export returns false and leaves no file
};

// limiter false: the master limiter is bypassed (stems), and the engine's limiter is on again afterwards.
bool exportMix(Engine& e, long frames, const char* path, std::string& error, const ExportControl* ctl = nullptr, bool limiter = true);

}  // namespace studio

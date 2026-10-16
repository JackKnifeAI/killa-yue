// Stems: a song split into drums, bass, other and vocals by Demucs (htdemucs), exactly as demucs's own
// apply_model does it (7.8 s windows at 44.1 kHz, a quarter overlapping, triangle weights, the last window
// padded with the song around it) after separate.py's normalisation (the song's mean and spread). It streams:
// only the overlap is kept between windows, every frame no later window touches is written at once, and a
// checkpoint after each window lets a stopped job carry on to the very same result. Off the audio thread.
#pragma once
#include <atomic>
#include <string>
#include <vector>

namespace studio {

// One window: in is [2][kWindow] (left, then right), out is [4][2][kWindow] (drums, bass, other, vocals).
struct StemModel {
    virtual bool run(const float* in, float* out, std::string& err) = 0;
    virtual ~StemModel() {}
};

class StemJob {
public:
    static constexpr int kWindow = 343980;  // 7.8 s at 44.1 kHz: htdemucs's segment
    static constexpr int kStride = 257985;  // int(0.75 x kWindow): apply_model's overlap of 0.25
    static constexpr const char* kNames[4] = {"drums", "bass", "other", "vocals"};

    static long windows(long frames44);  // windows for a song of that many frames at 44.1 kHz

    // song: a stereo or mono WAV at any rate; out: where drums.wav ... vocals.wav go (at outRate, with peak
    // files); work: the job's own folder (its checkpoint and partial files, removed when done).
    StemJob(const std::string& song, const std::string& out, const std::string& work, int outRate);
    bool run(StemModel& m, std::atomic<bool>* cancel, std::string& err);
    double progress() const { return progress_.load(); }

private:
    bool prepare(std::string& err);  // the song at 44.1 kHz, its mean and spread
    bool loadState();
    bool saveState(std::string& err);
    bool finish(std::string& err);   // the stems at the project's rate

    std::string song_, out_, work_, in44_;
    int outRate_;
    long n_ = 0;            // song frames at 44.1 kHz
    double mean_ = 0, sd_ = 1;
    long done_ = 0;         // windows finished
    long written_ = 0;      // frames of every stem finalised
    std::vector<float> acc_, wsum_;  // the overlap carried to the next window: 8 x tail, tail
    std::atomic<double> progress_{0};
};

}  // namespace studio

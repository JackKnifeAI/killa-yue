// The Studio's spoken voice (libkillavoice.so): Kokoro-82M through the sherpa-onnx C API, run in the ":ai"
// process. It is downloaded with the model, beside libsherpa-onnx-c-api.so and libonnxruntime.so, which are loaded
// first by full path. Apache-2.0, like sherpa-onnx and Kokoro.
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "c-api.h"

static char* str(JNIEnv* e, jstring s) {
    if (!s) return NULL;
    const char* c = (*e)->GetStringUTFChars(e, s, NULL);
    char* out = strdup(c ? c : "");
    if (c) (*e)->ReleaseStringUTFChars(e, s, c);
    return out;
}

// Speaks text with voice sid (0-53) at speed into a 24 kHz mono WAV. dir is the unpacked model folder; lang is an
// espeak language hint ("en-us", "es", "fr-fr", "hi", "it", "ja", "pt-br", "cmn") for the non-English voices.
// Returns the number of samples written, or a negative number on failure.
JNIEXPORT jint JNICALL Java_ai_jackknife_killayue_studio_ui_AiHost_nSpeak(JNIEnv* e, jclass cls, jstring dirS,
                                                                       jstring langS, jstring textS, jint sid,
                                                                       jfloat speed, jint threads, jstring outS) {
    (void)cls;
    char *dir = str(e, dirS), *lang = str(e, langS), *text = str(e, textS), *out = str(e, outS);
    char model[1024], voices[1024], tokens[1024], data[1024], dict[1024], lexicon[2048];
    snprintf(model, sizeof model, "%s/model.onnx", dir);
    snprintf(voices, sizeof voices, "%s/voices.bin", dir);
    snprintf(tokens, sizeof tokens, "%s/tokens.txt", dir);
    snprintf(data, sizeof data, "%s/espeak-ng-data", dir);
    snprintf(dict, sizeof dict, "%s/dict", dir);
    snprintf(lexicon, sizeof lexicon, "%s/lexicon-us-en.txt,%s/lexicon-zh.txt", dir, dir);
    SherpaOnnxOfflineTtsConfig config;
    memset(&config, 0, sizeof config);
    config.model.kokoro.model = model;
    config.model.kokoro.voices = voices;
    config.model.kokoro.tokens = tokens;
    config.model.kokoro.data_dir = data;
    config.model.kokoro.dict_dir = dict;
    config.model.kokoro.length_scale = 1.0f;
    int english = !lang || !*lang || strncmp(lang, "en", 2) == 0 || strcmp(lang, "cmn") == 0;
    config.model.kokoro.lexicon = english ? lexicon : "";
    config.model.kokoro.lang = lang ? lang : "";
    config.model.num_threads = threads > 0 ? threads : 4;
    config.model.provider = "cpu";
    config.max_num_sentences = 1;
    jint result = -1;
    const SherpaOnnxOfflineTts* tts = SherpaOnnxCreateOfflineTts(&config);
    if (tts) {
        const SherpaOnnxGeneratedAudio* audio = SherpaOnnxOfflineTtsGenerate(tts, text, sid, speed > 0 ? speed : 1.0f);
        if (audio) {
            result = audio->n > 0 && SherpaOnnxWriteWave(audio->samples, audio->n, audio->sample_rate, out) ? audio->n : -2;
            SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio);
        }
        SherpaOnnxDestroyOfflineTts(tts);
    }
    free(dir);
    free(lang);
    free(text);
    free(out);
    return result;
}

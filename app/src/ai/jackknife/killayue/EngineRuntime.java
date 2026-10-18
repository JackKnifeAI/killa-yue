package ai.jackknife.killayue;

import android.content.Context;
import java.io.File;
import java.util.Map;

/** Song-engine routing. CPU and system Vulkan use runtime-selected portable CPU kernels. */
final class EngineRuntime {
    static final String[] DRIVERS = {"turnip", "system"};
    static final String[] DRIVER_LABELS = {"Bundled driver (Adreno)", "System driver (experimental)"};

    static String driver(Context c) {
        return c.getSharedPreferences("cfg", 0).getString("gpu_driver", EngineChoice.defaultDriver(PhoneCheck.adrenoNumber()));
    }

    static String device(Context c) {
        return c.getSharedPreferences("cfg", 0).getString("device", "Vulkan0");
    }

    static boolean bundledGpu(Context c) {
        return EngineChoice.bundledGpu(device(c), driver(c));
    }

    static String executable(Context c) {
        return c.getApplicationInfo().nativeLibraryDir + "/" + EngineChoice.executable(device(c), driver(c));
    }

    static String library(Context c) {
        return EngineChoice.library(device(c), driver(c));
    }

    /** Private filenames keep the legacy engine from discovering the new shared backends. */
    static File prepareBackends(Context c, boolean cpu) throws Exception {
        File lib = new File(c.getApplicationInfo().nativeLibraryDir);
        File dir = new File(c.getFilesDir(), cpu ? "song-backends-cpu" : "song-backends-system");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("Cannot prepare the song engine folder");
        File[] files = lib.listFiles();
        int variants = 0;
        if (files != null) for (File f : files) {
            String name = f.getName();
            if (name.startsWith("libkycpu-") && name.endsWith(".so")) {
                link(new File(dir, "libggml-cpu-" + name.substring("libkycpu-".length())), f);
                variants++;
            }
        }
        if (variants == 0) throw new Exception("This APK is missing its portable CPU kernels");
        File gpu = new File(dir, "libggml-vulkan.so");
        if (cpu) gpu.delete();
        else {
            File source = new File(lib, "libkyvulkan.so");
            if (!source.isFile()) throw new Exception("This APK is missing its system GPU engine");
            link(gpu, source);
        }
        return dir;
    }

    private static void link(File destination, File source) throws Exception {
        destination.delete();
        android.system.Os.symlink(source.getAbsolutePath(), destination.getAbsolutePath());
    }

    /** Remove inherited driver selection before adding this launch's explicit configuration. */
    static void clearDriverEnvironment(Map<String, String> env) {
        for (String name : new String[] {"VK_DRIVER_FILES", "VK_ICD_FILENAMES", "GGML_DISABLE_VULKAN",
                "GGML_BACKEND_PATH", "YUE_BACKEND_DIR", "TU_KGSL_PRIORITY", "LD_LIBRARY_PATH"}) env.remove(name);
    }
}

package ai.jackknife.killayue;

/** Shared routing policy for executable and JNI launches; no Android dependency. */
final class EngineChoice {
    static String defaultDriver(int adreno) {
        return adreno == 0 || adreno >= 600 ? "turnip" : "system";
    }

    static boolean bundledGpu(String device, String driver) {
        return "Vulkan0".equals(device) && "turnip".equals(driver);
    }

    static String library(String device, String driver) {
        return bundledGpu(device, driver) ? "yueengine" : "yueengine_portable";
    }

    static String executable(String device, String driver) {
        return bundledGpu(device, driver) ? "libyueserver.so" : "libyueserver_portable.so";
    }
}

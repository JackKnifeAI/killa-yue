package ai.jackknife.killayue;

public class EngineChoiceTest {
    private static int passed;
    private static void check(boolean value, String why) {
        if (!value) throw new AssertionError(why);
        passed++;
    }

    public static void main(String[] args) {
        // Existing supported GPU users keep Turnip when upgrading without a driver preference.
        for (int adreno : new int[] {0, 600, 650, 730, 740, 750, 830}) {
            String driver = EngineChoice.defaultDriver(adreno);
            check("turnip".equals(driver), "Adreno default driver " + adreno);
            check("yueengine".equals(EngineChoice.library("Vulkan0", driver)), "Adreno JNI preserved " + adreno);
            check("libyueserver.so".equals(EngineChoice.executable("Vulkan0", driver)), "Adreno child preserved " + adreno);
        }
        for (int adreno : new int[] {-1, 530})
            check("system".equals(EngineChoice.defaultDriver(adreno)), "Non-bundled GPU default " + adreno);
        // CPU must stay portable even if the saved driver is Turnip.
        for (String driver : new String[] {"turnip", "system"}) {
            check(!EngineChoice.bundledGpu("CPU", driver), "CPU must not initialize Turnip");
            check("yueengine_portable".equals(EngineChoice.library("CPU", driver)), "CPU JNI baseline");
            check("libyueserver_portable.so".equals(EngineChoice.executable("CPU", driver)), "CPU child baseline");
        }
        check(!EngineChoice.bundledGpu("Vulkan0", "system"), "Explicit system GPU must not use Turnip");
        check("yueengine_portable".equals(EngineChoice.library("Vulkan0", "system")), "System JNI");
        check("libyueserver_portable.so".equals(EngineChoice.executable("Vulkan0", "system")), "System child");
        System.out.println("EngineChoiceTest: " + passed + " checks passed");
    }
}

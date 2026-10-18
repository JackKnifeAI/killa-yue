package ai.jackknife.killayue.compat;
public final class ProcessFixture {
    public static void main(String[] args) throws Exception {
        if ("hang".equals(args[0])) Thread.sleep(30000);
        else if ("flood".equals(args[0])) for (int i = 0; i < 100000; i++) System.out.println("untrusted native output");
        else if ("stderr".equals(args[0])) { System.err.println("VK_ERROR_DEVICE_LOST"); for (int i = 0; i < 10000; i++) System.err.println("private log must not enter report"); System.out.print("result"); }
        else System.exit(7);
    }
}

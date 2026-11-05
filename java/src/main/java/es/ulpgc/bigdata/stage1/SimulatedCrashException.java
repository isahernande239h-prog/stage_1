package es.ulpgc.bigdata.stage1;

/** For the recovery experiments: simulates the process dying halfway through. */
public class SimulatedCrashException extends RuntimeException {
    public SimulatedCrashException(String message) {
        super(message);
    }
}

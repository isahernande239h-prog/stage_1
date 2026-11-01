package es.ulpgc.bigdata.stage1;

/** Para los experimentos de recuperacion: simula que el proceso muere a mitad. */
public class SimulatedCrashException extends RuntimeException {
    public SimulatedCrashException(String message) {
        super(message);
    }
}

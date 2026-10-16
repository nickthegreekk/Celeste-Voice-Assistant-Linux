/*
 * OnnxSmokeTest — the check that the packaged ONNX Runtime still loads after the packaging cut.
 *
 * `PKGBUILD`'s `check()` runs this against the **built app image**, with the image's own runtime
 * and the image's own jars. That is deliberate: the cut it verifies — dropping the Windows, macOS
 * and linux-aarch64 natives out of `onnxruntime-*.jar`, measured at 90 MB -> 5.7 MB — is a change
 * to a file no test in the Gradle build can see, because `:core`'s tests compile against the Gradle
 * dependency, which is the *untrimmed* jar. `VADDetector` is the call site: it asks the environment
 * for a session over the model bytes, and that is exactly what this does.
 *
 * It is written in Java rather than Kotlin on purpose: nothing here should need Gradle, a Kotlin
 * compiler or a source file on a classpath. `check()` compiles it with the build host's `javac` and
 * runs it on the packaged runtime, so what it exercises is the shipped artefact and nothing else.
 *
 * Run:  java --enable-native-access=ALL-UNNAMED -cp "<image>/lib/app/*" OnnxSmokeTest <model.onnx>
 */
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.nio.file.Files;
import java.nio.file.Path;

public final class OnnxSmokeTest {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: OnnxSmokeTest <model.onnx>");
            System.exit(2);
        }
        byte[] model = Files.readAllBytes(Path.of(args[0]));
        try (OrtEnvironment env = OrtEnvironment.getEnvironment();
             OrtSession session = env.createSession(model)) {
            System.out.println("onnx native loaded — session inputs: " + session.getInputNames());
        }
    }
}

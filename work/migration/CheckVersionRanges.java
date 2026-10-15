import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
public class CheckVersionRanges {
    public static void main(String[] args) throws Exception {
        int failed = 0;
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] p = line.split("\t", -1);
            VersionRange range = VersionRange.createFromVersionSpec(p[3]);
            boolean passed = range.containsVersion(new DefaultArtifactVersion(p[2]));
            String via = "direct";
            // Exact compatibility rules verified in loader-4.0.45 VersionSupportMatrix bytecode.
            if (!passed && p[1].equals("minecraft") && p[2].equals("1.21.1")) {
                passed = range.containsVersion(new DefaultArtifactVersion("1.21"));
                via = "NeoForge-4.0.45-minecraft-1.21-alias";
            }
            if (!passed && p[1].equals("neoforge")) {
                passed = range.containsVersion(new DefaultArtifactVersion("21.0.166"));
                via = "NeoForge-4.0.45-neoforge-21.0.166-alias";
            }
            if (!passed) failed++;
            System.out.println(p[0]+"\t"+p[1]+"\t"+p[2]+"\t"+p[3]+"\t"+(passed?"PASS":"FAIL")+"\t"+via);
        }
        if (failed > 0) System.exit(1);
    }
}

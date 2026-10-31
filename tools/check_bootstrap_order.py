"""Read-only startup wiring evidence. This does not launch Minecraft or certify game acceptance."""
from __future__ import annotations
import argparse
import io
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def method(text: str, name: str) -> str:
    start = next((m.start() for m in re.finditer(r"^  (?:public|private|protected).*", text, re.M)
                  if name in m.group()), None)
    if start is None:
        raise AssertionError(f"Missing bytecode method: {name}")
    end = re.search(r"^  (?:public|private|protected|static).*", text[start + 3:], re.M)
    return text[start:start + 3 + end.start()] if end else text[start:]

def javap(java_tool: str, jar: Path, class_name: str) -> str:
    return subprocess.run([java_tool, "-c", "-p", "-classpath", str(jar), class_name],
                          check=True, capture_output=True, text=True, encoding="utf-8").stdout

def verify_source() -> None:
    locator = (ROOT / "loader/forge/fml47/src/main/java/pl/skidam/automodpack_loader_core_forge/EarlyModLocator.java").read_text(encoding="utf-8")
    assert re.search(r"initArguments\([^)]*\)\s*\{[^}]*new Preload\(net\.minecraftforge\.fml\.loading\.FMLPaths\.GAMEDIR\.get\(\)\)", locator, re.S)
    assert "new Preload" not in locator[locator.index("Stream<Path> scanCandidates"):]
    preload = (ROOT / "loader/core/src/main/java/pl/skidam/automodpack_loader_core/Preload.java").read_text(encoding="utf-8")
    assert "StagedInstaller.applyPending(gameRoot, trust, consents::hasAnyGrant)" in preload
    assert all(x not in preload for x in ("SelfUpdater.update", "ModpackUpdater", "OneCrl", "requestServerModpackContent", "System.exit"))
    mixins = json.loads((ROOT / "src/main/resources/automodpack-main.mixins.json").read_text(encoding="utf-8"))
    assert set(mixins["client"]) == {"core.ClientLoginNetworkHandlerAccessor", "core.ClientLoginNetworkHandlerMixin"}
    assert "core.OnlineAuthenticationProofMixin" in mixins["mixins"]
    for name in ("selectModpack", "deleteFilesMarkedForDeletionByTheServer", "requestServerModpackContent"):
        legacy = (ROOT / "loader/core/src/main/java/pl/skidam/automodpack_loader_core/client/ModpackUtils.java").read_text(encoding="utf-8")
        assert re.search(r"public static [^\n]+ " + name + r"\([^\n]+\)[^{]*\{\s*throw new SecurityException", legacy)
    print("PASS source: consent-gated offline apply is wired to locator initialization; legacy entrypoints disabled")

def verify_artifact(path: Path, java_tool: str) -> None:
    with zipfile.ZipFile(path) as jar:
        services = jar.read("META-INF/services/net.minecraftforge.forgespi.locating.IModLocator").decode()
        assert "pl.skidam.automodpack_loader_core.EarlyModLocator" in services
        with zipfile.ZipFile(io.BytesIO(jar.read("META-INF/jarjar/automodpack-mod.jar"))) as mod:
            mixins = json.loads(mod.read("automodpack-main.mixins.json"))
            assert "core.OnlineAuthenticationProofMixin" in mixins["mixins"]
            assert "pl/skidam/automodpack/client/oss/OssLoginSession.class" in mod.namelist()
    early = javap(java_tool, path, "pl.skidam.automodpack_loader_core.EarlyModLocator")
    assert 'Preload."<init>"' in method(early, "initArguments(")
    assert "Preload" not in method(early, "scanCandidates(")
    preload = javap(java_tool, path, "pl.skidam.automodpack_loader_core.Preload")
    assert "StagedInstaller.applyPending" in preload
    assert all(x not in preload for x in ("SelfUpdater.update", "System.exit", "ModpackUpdater", "OneCrl"))
    print("PASS artifact bytecode: service registration, early apply and real client session are packaged")

def verify_forge(path: Path, java_tool: str) -> None:
    text = javap(java_tool, path, "net.minecraftforge.fml.loading.moddiscovery.ModDiscoverer")
    constructor = method(text, "ModDiscoverer(java.util.Map")
    assert "java/util/List.forEach" in constructor and "scanMods" not in constructor
    assert "IModLocator.initArguments" in text
    assert "IModLocator.scanMods" in method(text, "discoverMods(")
    print("PASS Forge bytecode: all locator initializers run during construction before discoverMods scans candidates")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact", type=Path)
    parser.add_argument("--forge-loader", type=Path)
    parser.add_argument("--javap", default="javap")
    args = parser.parse_args()
    verify_source()
    if args.artifact: verify_artifact(args.artifact, args.javap)
    if args.forge_loader: verify_forge(args.forge_loader, args.javap)

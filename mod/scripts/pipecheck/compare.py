#!/usr/bin/env python3
"""Luxium vs Iris, program by program, on the real Metal driver.

  python3 -I mod/scripts/pipecheck/compare.py <luxium dump world dir> <iris patched_shaders dir> <out dir>

- Luxium side: the MSL Luxium's game run dumped (LUXIUM_DUMP_SHADERS), exactly what Metallum compiles.
- Iris side: Iris's patched GLSL (IRIS_DUMP=1 in iris-compare/run.sh), taken to MSL the way the
  game's Metal path would take it: glslang -V -R (relaxed Vulkan rules, which accept GL's loose
  uniforms) --amb --aml --target-env vulkan1.2, then spirv-cross with Metallum's options.
- Both go through pipecheck (a real MTLRenderPipelineState per pair; see pipecheck.m).

Prints, per program: whether each side links, colour outputs, and the pack textures each side
actually uses, with differences flagged. Vertex inputs differ by design (Iris feeds its own
extended vertex formats; Luxium derives the extras from vanilla's), so they're listed, not diffed.
Writes everything (MSL, JSON lines, report.txt) to <out dir>.
"""
import json
import os
import re
import subprocess
import sys

# Iris shader key (lower case, as Iris's ShaderPrinter names files) -> Luxium dumped pipeline.
# Program and alpha test match (Iris ShaderKey vs Luxium's takeovers); see luxium-offline-check.
PAIRS = {
    "sodium_terrain_solid": "terrain_solid",
    "sodium_terrain_cutout": "terrain_cutout",
    "sodium_terrain_translucent": "terrain_translucent",
    "shadow_sodium_terrain_solid": "terrain_shadow_solid",
    "shadow_sodium_terrain_cutout": "terrain_shadow_cutout",
    "shadow_sodium_terrain_translucent": "terrain_shadow_translucent",
    "entities_solid": "gbuffers_entities_pipeline_entity_solid",
    "entities_cutout": "gbuffers_entities_pipeline_entity_cutout",
    "entities_translucent": "gbuffers_entities_translucent_pipeline_entity_translucent",
    "entities_eyes": "gbuffers_spidereyes_pipeline_eyes",
    "hand_cutout": "gbuffers_hand_pipeline_entity_cutout",
    "hand_translucent": "gbuffers_hand_water_pipeline_entity_translucent",
    "particles": "gbuffers_particles_pipeline_opaque_particle",
    "particles_trans": "gbuffers_particles_translucent_pipeline_translucent_particle",
    "weather": "gbuffers_weather_pipeline_weather",
    "block_entity": "gbuffers_block_pipeline_entity_cutout_cull",
    "beacon": "gbuffers_beaconbeam_pipeline_beacon_beam_opaque",
    "shadow_entities_cutout": "shadow_pipeline_entity_cutout",
}
for n in ["deferred1", "composite", "composite1", "composite2", "composite3", "composite4", "composite5",
          "composite6", "composite7", "composite8", "final"]:
    PAIRS[n] = "pack_" + n

# Same texture under each runtime's name (game textures are renamed differently by each).
ALIASES = {
    "gtexture": "albedo", "tex": "albedo", "texture": "albedo", "Sampler0": "albedo",
    "lightmap": "lightmap", "Sampler2": "lightmap",
    "iris_overlay": "overlay", "Sampler1": "overlay",
}

HERE = os.path.dirname(os.path.abspath(__file__))


def run(cmd):
    p = subprocess.run(cmd, capture_output=True, text=True)
    return p.returncode, (p.stdout + p.stderr)


def first_error(text):
    for line in text.splitlines():
        if "ERROR" in line or "error" in line:
            return line.strip()[:200]
    return text.strip().splitlines()[0][:200] if text.strip() else "?"


def iris_to_msl(src, out_base, stage):
    """Iris GLSL -> SPIR-V -> MSL. Returns (path, None) or (None, error)."""
    spv = out_base + "." + stage + ".spv"
    code, text = run(["glslangValidator", "-V", "-R", "--amb", "--aml", "--target-env", "vulkan1.2",
                      "-S", stage, src, "-o", spv])
    if code != 0:
        return None, "glslang: " + first_error(text)
    msl = out_base + "." + stage + ".metal"
    cross = ["spirv-cross", spv, "--msl", "--msl-version", "40000", "--msl-decoration-binding",
             "--msl-texture-buffer-native"]
    if stage == "vert":
        cross.append("--flip-vert-y")
    code, text = run(cross + ["--output", msl])
    if code != 0:
        return None, "spirv-cross: " + first_error(text)
    return msl, None


def find_iris(iris_dir, key, ext):
    for f in sorted(os.listdir(iris_dir)):
        if re.fullmatch(r"\d+_" + re.escape(key) + re.escape(ext), f):
            return os.path.join(iris_dir, f)
    return None


def texture_set(names):
    out = set()
    for n in names:
        n = n[3:] if n.startswith("vs:") else n
        n = re.sub(r"Smplr$", "", n)
        out.add(ALIASES.get(n, n))
    return out


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    lux_dir, iris_dir, out = sys.argv[1:]
    os.makedirs(os.path.join(out, "iris"), exist_ok=True)
    tool = os.path.join(out, "pipecheck")
    code, text = run(["clang", "-fobjc-arc", "-O1", "-framework", "Foundation", "-framework", "Metal",
                      os.path.join(HERE, "pipecheck.m"), "-o", tool])
    if code != 0:
        print("pipecheck build failed:", text)
        return 1

    lux_list, iris_list, pre = [], [], {}
    for key, lux in PAIRS.items():
        lv, lf = (os.path.join(lux_dir, lux + s) for s in (".vert.metal", ".frag.metal"))
        iv_src, if_src = find_iris(iris_dir, key, ".vsh"), find_iris(iris_dir, key, ".fsh")
        if not (os.path.exists(lv) and os.path.exists(lf)) and not (iv_src and if_src):
            continue
        if os.path.exists(lv) and os.path.exists(lf):
            lux_list.append(f"{key}\t{lv}\t{lf}")
        else:
            pre.setdefault(key, {})["luxium"] = "not drawn by Luxium in this run"
        if iv_src and if_src:
            base = os.path.join(out, "iris", key)
            iv, err_v = iris_to_msl(iv_src, base, "vert")
            ifr, err_f = iris_to_msl(if_src, base, "frag")
            if iv and ifr:
                iris_list.append(f"{key}\t{iv}\t{ifr}")
            else:
                pre.setdefault(key, {})["iris"] = err_v or err_f
        else:
            pre.setdefault(key, {})["iris"] = "not in Iris's dump (not compiled in that run)"

    results = {}
    for side, lst in (("luxium", lux_list), ("iris", iris_list)):
        path = os.path.join(out, side + ".tsv")
        with open(path, "w") as fh:
            fh.write("\n".join(lst) + "\n")
        _, text = run([tool, path])
        with open(os.path.join(out, side + ".jsonl"), "w") as fh:
            fh.write(text)
        for line in text.splitlines():
            if line.startswith("{"):
                r = json.loads(line)
                results.setdefault(r["name"], {})[side] = r

    lines = []
    counts = {"both ok": 0, "texture diffs": 0, "output diffs": 0, "failures": 0}
    for key in PAIRS:
        if key not in results and key not in pre:
            continue
        lux = results.get(key, {}).get("luxium")
        iris = results.get(key, {}).get("iris")
        lines.append(f"== {key}  (Luxium: {PAIRS[key]})")
        for side, r in (("luxium", lux), ("iris", iris)):
            if r is None:
                lines.append(f"  {side:6}: {pre.get(key, {}).get(side, 'missing')}")
            elif not r["ok"]:
                counts["failures"] += 1
                lines.append(f"  {side:6}: FAIL {r['error']}")
        if lux and iris and lux["ok"] and iris["ok"]:
            counts["both ok"] += 1
            lt, it = texture_set(lux["textures"]), texture_set(iris["textures"])
            lo, io = lux["colorOutputs"], iris["colorOutputs"]
            lines.append(f"  outputs: luxium {lo}  iris {io}" + ("" if len(lo) in (len(io), len(io) + 1) else "  <-- DIFF"))
            if len(lo) not in (len(io), len(io) + 1):
                counts["output diffs"] += 1
            if lt != it:
                counts["texture diffs"] += 1
                if it - lt:
                    lines.append(f"  textures only Iris uses:   {sorted(it - lt)}  <-- DIFF")
                if lt - it:
                    lines.append(f"  textures only Luxium uses: {sorted(lt - it)}  <-- DIFF")
            else:
                lines.append(f"  textures: same ({len(lt)})")
            lines.append(f"  vertex inputs: luxium {lux['vertexInputs']}")
            lines.append(f"                 iris   {iris['vertexInputs']}")
    summary = (f"{len(results)} programs compared: {counts['both ok']} link on both sides, "
               f"{counts['failures']} failures, {counts['texture diffs']} with texture differences, "
               f"{counts['output diffs']} with output-count differences "
               f"(Luxium's extra coverage-mask output is not counted)")
    report = summary + "\n\n" + "\n".join(lines) + "\n"
    with open(os.path.join(out, "report.txt"), "w") as fh:
        fh.write(report)
    print(summary)
    print("report:", os.path.join(out, "report.txt"))
    return 0


if __name__ == "__main__":
    sys.exit(main())

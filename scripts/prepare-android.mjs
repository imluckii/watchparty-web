#!/usr/bin/env node
// Copies the production web bundle into the Android shell's assets and trims the
// pieces the app never serves (source maps, iOS splash screens, store screenshots).
//
// Usage: pnpm build && node scripts/prepare-android.mjs
import { cp, mkdir, readFile, rm, writeFile, readdir, stat } from 'node:fs/promises';
import { join, relative } from 'node:path';

const repo = new URL('..', import.meta.url).pathname.replace(/\/$/, '');
const buildDir = join(repo, 'build');
const outDir = join(repo, 'android', 'app', 'src', 'main', 'assets', 'web');

const SKIP_DIRS = new Set(['screenshots', '.well-known', join('images', 'splash')]);
const SKIP_FILE = /(\.map$|^service-worker\.js$|^workbox-.*\.js$)/;

async function walk(dir, base = dir) {
    const out = [];
    for (const entry of await readdir(dir, { withFileTypes: true })) {
        const path = join(dir, entry.name);
        const rel = relative(base, path);
        if (entry.isDirectory()) {
            if (SKIP_DIRS.has(rel)) continue;
            out.push(...await walk(path, base));
        } else if (!SKIP_FILE.test(entry.name)) {
            out.push(rel);
        }
    }
    return out;
}

async function main() {
    try {
        await stat(join(buildDir, 'index.html'));
    } catch {
        console.error('build/index.html is missing — run `pnpm build` first.');
        process.exit(1);
    }

    await rm(outDir, { recursive: true, force: true });
    await mkdir(outDir, { recursive: true });

    const files = await walk(buildDir);
    for (const rel of files) {
        const dest = join(outDir, rel);
        await mkdir(join(dest, '..'), { recursive: true });
        await cp(join(buildDir, rel), dest);
    }

    // index.html: drop iOS-only splash links and the two remote SDK scripts that
    // can never work inside the shell, and give the document the app's name.
    const indexPath = join(outDir, 'index.html');
    let html = await readFile(indexPath, 'utf8');
    const before = html.length;
    html = html.replace(/<link rel="apple-touch-startup-image"[^>]*>/g, '');
    html = html.replace(/<script[^>]+(?:gstatic\.com|appleid\.cdn-apple\.com)[^>]*><\/script>/g, '');
    html = html.replace(/<title>.*?<\/title>/g, '<title>Watchparty</title>');
    html = html.replace(
        /<meta name="apple-mobile-web-app-title" content="[^"]*">/g,
        '<meta name="apple-mobile-web-app-title" content="Watchparty">'
    );
    await writeFile(indexPath, html);

    const scriptDirName = (await readdir(outDir, { withFileTypes: true }))
        .find((entry) => entry.isDirectory() && /^[0-9a-f]{40}$/.test(entry.name))?.name;
    if (!scriptDirName) {
        console.error('no hashed script directory in the bundle');
        process.exit(1);
    }

    // The Rust core defaults its streaming server to 127.0.0.1:11470, both as the profile
    // default and in the local-addon transport URL. In the shell the engine is always
    // reached through this app's own loopback origin, so repoint those two literals.
    // Same byte length, so offsets inside the wasm module stay valid.
    const wasmPath = join(outDir, scriptDirName, 'binaries', 'stremio_core_web_bg.wasm');
    try {
        const wasm = await readFile(wasmPath);
        const from = Buffer.from('http://127.0.0.1:11470', 'latin1');
        const to = Buffer.from('http://127.0.0.1:11471', 'latin1');
        const count = wasm.toString('latin1').split(from.toString('latin1')).length - 1;
        if (count === 0) {
            throw new Error('no built-in streaming server URL found in the core wasm');
        }
        const patched = Buffer.from(wasm.toString('latin1').split(from.toString('latin1')).join(to.toString('latin1')), 'latin1');
        await writeFile(wasmPath, patched);
        console.log(`core wasm: repointed ${count} built-in engine URL(s)`);
    } catch (err) {
        console.error(`could not patch the core wasm: ${err.message}`);
        process.exit(1);
    }

    // stremio-video hardcodes the engine port for external audio/subtitle track metadata.
    // In the shell the engine is reached through this app's origin, never directly.
    const mainPath = join(outDir, scriptDirName, 'scripts', 'main.js');
    const bundle = await readFile(mainPath, 'utf8');
    const hits = (bundle.match(/http:\/\/127\.0\.0\.1:11470/g) || []).length;
    if (hits > 0) {
        await writeFile(mainPath, bundle.split('http://127.0.0.1:11470').join('http://127.0.0.1:11471'));
        console.log(`main.js: repointed ${hits} hardcoded engine URL(s)`);
    }

    const manifestPath = join(outDir, 'manifest.json');
    try {
        const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
        manifest.name = 'Watchparty';
        manifest.short_name = 'Watchparty';
        manifest.description = 'Your Stremio library, as a native app.';
        await writeFile(manifestPath, JSON.stringify(manifest, null, 4));
    } catch {
        // no manifest in this bundle — nothing to rename
    }

    let bytes = 0;
    for (const rel of await walk(outDir, outDir)) {
        bytes += (await stat(join(outDir, rel))).size;
    }
    console.log(
        `assets/web: ${files.length} files, ${(bytes / 1048576).toFixed(1)} MiB` +
        ` (index.html trimmed ${before - html.length} bytes)`
    );
}

main().catch((err) => {
    console.error(err);
    process.exit(1);
});

// Runs the band navigation's e2e checks in Firefox (the engine of the glasses' GeckoView) and
// prints ok/FAIL per check; exits 1 on any failure. Screenshots go to $LUMEN_E2E_OUT (default
// $TMPDIR/lumen-e2e).
//
//   NODE_PATH=/path/to/node_modules node scripts/e2e/run.mjs [--offline]
//
// Needs Playwright with its Firefox (`npx playwright install firefox`). --offline skips the real
// sites (m.youtube.com, en.wikipedia.org). Run scripts/gen-gecko-content.py first after changing
// mrbd-shim.js: the checks load the generated page.js.
import { check, launch, outDir, results } from './lib.mjs';
import * as bandNav from './band-nav.mjs';
import * as realSites from './real-sites.mjs';

const offline = process.argv.includes('--offline');
const out = outDir();
const browser = await launch();
try {
  console.log('# Page API and generic navigation (fixtures)');
  await bandNav.run(browser, out);
  if (!offline) {
    console.log('# Real sites');
    await realSites.run(browser, out);
  }
} catch (e) {
  check('runner', false, e.stack || String(e));
} finally {
  await browser.close();
}
const { passes, failures } = results();
console.log(`\n${passes} passed, ${failures} failed. Screenshots: ${out}`);
process.exit(failures ? 1 : 0);

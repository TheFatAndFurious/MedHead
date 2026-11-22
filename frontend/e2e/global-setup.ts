import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repositoryRoot = fileURLToPath(new URL('../..', import.meta.url));

// Every successful reservation consumes a demo bed. Start each run from the
// initial demo data by recreating the DEMO-* hospitals of the running stack.
// The stack must already be running: --no-deps leaves its services untouched.
// Set E2E_RESET_SEED=false to keep the current data (for example when the
// stack was reset by another step, or is not managed by Docker Compose).
export default function globalSetup() {
	if (process.env.E2E_RESET_SEED === 'false') return;
	execFileSync('docker', ['compose', '--profile', 'maintenance', 'run', '--rm', '--no-deps', 'reset-seed'], {
		cwd: repositoryRoot,
		stdio: 'inherit'
	});
}

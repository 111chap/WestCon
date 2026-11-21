const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

function inspectFile(name, content) {
  const issues = [];
  const parts = name.split('/');
  const base = parts.at(-1);
  if (parts.includes('node_modules') || parts.includes('.git') ||
      /\.(?:db|sqlite|sqlite3)(?:-(?:wal|shm|journal))?$/i.test(name) ||
      (/^\.env(?:\.|$)/.test(base) && base !== '.env.example') ||
      ['.npmrc', '.netrc', '.git-credentials', 'google-services.json', 'keystore.properties', 'signing.properties'].includes(base) || /\.(?:jks|keystore|p12|pfx|apk|aab)$/i.test(name)) {
    issues.push('Private runtime/configuration file must not be tracked.');
  }
  const patterns = [
    ['Private key', /-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----/],
    ['GitHub credential', /\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b/],
    ['AWS access key', /\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/],
    ['Embedded login token', /\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/],
  ];
  for (const [label, pattern] of patterns) if (pattern.test(content)) issues.push(label + ' must not be committed.');
  if (/^\.github\/workflows\/.*\.ya?ml$/.test(name)) {
    if (/\bpull_request_target\b/.test(content)) issues.push('Use pull_request with read-only permissions for untrusted contributions.');
    if (/permissions:\s*write-all\b/.test(content)) issues.push('Broad workflow write permissions are not allowed.');
  }
  return issues.map(reason => ({ file: name, reason }));
}

function scan(root) {
  const names = execFileSync('git', ['ls-files', '-z'], { cwd: root }).toString().split('\0').filter(Boolean);
  const issues = [];
  for (const name of names) {
    const location = path.join(root, name);
    const data = fs.readFileSync(location);
    issues.push(...inspectFile(name, data.includes(0) ? '' : data.toString('utf8')));
  }
  return issues;
}

if (require.main === module) {
  const issues = scan(process.cwd());
  if (issues.length) {
    console.error(JSON.stringify(issues, null, 2));
    process.exitCode = 1;
  } else console.log('Repository guardrails passed.');
}
module.exports = { inspectFile, scan };


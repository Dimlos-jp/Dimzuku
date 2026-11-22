# Security Policy

## Supported Versions

Security fixes are provided **only** for the following versions of Dimzuku:

| Version | Supported |
| ------- | --------- |
| Latest commit on the default branch | ✅ |
| Latest release (if any) | ✅ |
| Older versions | ❌ |

Vulnerabilities in the upstream Shizuku project should be reported to that project.

Reports affecting unsupported versions may be closed without action.

---

## Reporting a Vulnerability

If you discover a security vulnerability in Dimzuku, please report it **privately** and practice responsible disclosure. [See how](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/report-a-vulnerability/privately-reporting-a-security-vulnerability).

Dimzuku, like Shizuku, provides access to **ADB-level and/or root-level privileges**, so security issues may have a high impact.

When reporting a vulnerability, you may include any of the following:

- Detailed step-by-step reproduction instructions (including granular or 1-by-2 steps)
- Screenshots or screen recordings
- Logs or crash output
- Proof-of-concept (PoC) code
- Test applications or scripts demonstrating the issue

Providing detailed information helps reproduce and fix the issue more efficiently.

---

## Testing Before Reporting

To reduce duplicate reports and false positives, please verify the issue under the following conditions:

Confirm that the issue reproduces on the **latest commit** (or on the latest release, if there is
one). If it does not, it may already be fixed.

---

## Responsible Disclosure

Due to the privileged nature of Dimzuku, vulnerabilities may be easily exploitable if disclosed prematurely.

Please follow these disclosure guidelines:

- Do **not** publicly disclose vulnerability details immediately
- Wait **at least 1 month after a release containing the fix** before sharing technical details that could reasonably lead to exploitation
  - This includes exploit write-ups, abuse techniques, code snippets, videos, or tutorials that demonstrate real-world attacks

This delay allows users adequate time to update and helps reduce the risk of active exploitation.

---

## What Not to Report

- Issues affecting only unsupported versions
- Vulnerabilities that require modifying Dimzuku itself to be exploitable
- Reports without any reasonable security impact

---

## Bug Bounty

Dimzuku does **not** offer a bug bounty or monetary rewards.

However, **responsible security reporters will be credited in release notes** or other public acknowledgements, at the maintainer's discretion.

---

## Acknowledgements

I appreciate the efforts of security researchers and community members who help improve Dimzuku's security. Responsible disclosure helps keep users safe and the ecosystem healthy.

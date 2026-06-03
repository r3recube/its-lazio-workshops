---
lab_id: ansible-aws
checked_at: 2026-06-02T19:58:22.455369+00:00
overall_status: MISSING_REQUIREMENTS
os_detected: darwin-arm64
---

# Prerequisites check report

| Tool | Required | Installed | Status |
|------|----------|-----------|--------|
| python3 | >= 3.9 | 3.13.12 | ✅ OK |
| ansible | >= 2.16 | 2.16.2 | ✅ OK |
| aws-cli | v2 | 2.25.11 | ✅ OK |
| jq | any | 1.7.1 | ✅ OK |
| curl | any | 8.7.1 | ✅ OK |
| ssh (OpenSSH) | >= 8.0 | 9.9p2 | ✅ OK |
| boto3 | any | not found | ❌ MISSING |
| botocore | any | not found | ❌ MISSING |

## Missing tools

- `boto3` — libreria Python richiesta dai moduli `amazon.aws` di Ansible
- `botocore` — dipendenza diretta di `boto3` (installata automaticamente con essa)

See `requirements.md` for installation instructions.

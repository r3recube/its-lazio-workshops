---
lab_id: cognito-identity-pool
version: 2
status: draft
generated_at: 2026-06-22T05:21:25.600699+00:00
based_on_evaluation: ./evaluation.md
---

# Amazon Cognito Identity Pool: OpenID Connect, OAuth 2.0, JWT e IAM Authorization

## Learning Objectives

- Comprendere cosa è un Amazon Cognito Identity Pool e come si differenzia dal User Pool
- Spiegare il flusso Authorization Code di OAuth 2.0 e come OpenID Connect lo estende
- Decodificare e analizzare un JWT token (header, payload, firma) e comprendere il suo ruolo nello scambio
- Distinguere i ruoli di Identity Provider (IdP) e Resource Provider (RP)
- Scrivere un'applicazione Python frontend che guidi il flusso di autenticazione Cognito Hosted UI
- Scrivere un backend Flask su EC2 che validi i JWT token e stampi tutte le claim
- Implementare autorizzazione IAM con credenziali temporanee ottenute tramite l'Identity Pool

---

## Prerequisites

### Tools required (with exact versions)

```bash
python3 --version        # >= 3.11
pip3 --version           # >= 23
aws --version            # AWS CLI v2
curl --version           # any recent
jq --version             # any recent
```

### Knowledge assumed

- HTTP request/response lifecycle, query string parameters, redirect responses (302)
- Concetto di autenticazione vs autorizzazione
- Basi di AWS CLI e CloudFormation (leggere output di uno stack)
- Familiarità con il terminale Linux/macOS

---

## Concetti chiave (leggi prima di iniziare)

**Amazon Cognito — due servizi, un nome:**

- **User Pool**: è un Identity Provider (IdP). Gestisce il registro utenti, sign-up, sign-in, MFA. Emette JWT token (ID token, access token, refresh token).
- **Identity Pool**: è un federation broker. Riceve un JWT da un IdP fidato (incluso un User Pool) e lo scambia con credenziali AWS temporanee via STS.

**OAuth 2.0 Authorization Code Flow:**
```
Utente → App → Cognito Hosted UI (/oauth2/authorize)
                    ↓  utente si autentica
Cognito → App (callback con authorization code)
App → Cognito (/oauth2/token: scambia code per token)
Cognito → App (ID token + access token + refresh token)
```

**OpenID Connect (OIDC):**
OAuth 2.0 è un framework di *autorizzazione*. OIDC è un livello di *identità* sopra OAuth 2.0. Aggiunge l'**ID token** (JWT con le claim di identità) e l'endpoint di discovery `/.well-known/openid-configuration`.

**Struttura JWT:**
```
<header_base64url>.<payload_base64url>.<signature_base64url>
```
- `header`: algoritmo + tipo token (JSON Base64URL-encoded)
- `payload`: claim (JSON Base64URL-encoded)
- `signature`: firma RS256 per rilevare manomissioni

**Identity Provider vs Resource Provider:**
- **Identity Provider (IdP)**: emette e verifica l'identità (Cognito User Pool). Risponde a: *chi è questo utente?*
- **Resource Provider (RP)**: si fida dell'IdP e usa il token per concedere accesso (la tua API Flask, AWS via IAM). Risponde a: *questo utente può fare X?*

---

## Setup

### 1. Variabili d'ambiente dallo stack CloudFormation

Lo stack `recube-lab-cognito-identity-pool` deve essere già attivo. Esporta tutti gli output come variabili d'ambiente — verranno usati in ogni task successivo.

```bash
# Recupera tutti gli output dello stack e impostali come variabili
export STACK_NAME="recube-lab-cognito-identity-pool"
export AWS_REGION="eu-west-1"

export USER_POOL_ID=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" --region "$AWS_REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='UserPoolId'].OutputValue" \
  --output text)

export USER_POOL_CLIENT_ID=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" --region "$AWS_REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='UserPoolClientId'].OutputValue" \
  --output text)

export IDENTITY_POOL_ID=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" --region "$AWS_REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='IdentityPoolId'].OutputValue" \
  --output text)

export COGNITO_DOMAIN=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" --region "$AWS_REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='CognitoDomain'].OutputValue" \
  --output text)

export COGNITO_DOMAIN_PREFIX=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" --region "$AWS_REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='CognitoDomainPrefix'].OutputValue" \
  --output text)

export BACKEND_URL=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" --region "$AWS_REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='BackendUrl'].OutputValue" \
  --output text)

export AUTHENTICATED_ROLE_ARN=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" --region "$AWS_REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='AuthenticatedRoleArn'].OutputValue" \
  --output text)

export EC2_INSTANCE_ID=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" --region "$AWS_REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='Ec2InstanceId'].OutputValue" \
  --output text)

export STAGING_BUCKET=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" --region "$AWS_REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='StagingBucketName'].OutputValue" \
  --output text)

export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
```

### 2. Cartella di lavoro

```bash
mkdir -p ~/cognito-lab/{frontend,backend}
cd ~/cognito-lab
```

### 3. Verifica setup

```bash
# Tutte le variabili devono essere non vuote
FAIL=0
for VAR in USER_POOL_ID USER_POOL_CLIENT_ID IDENTITY_POOL_ID \
           COGNITO_DOMAIN COGNITO_DOMAIN_PREFIX BACKEND_URL \
           AUTHENTICATED_ROLE_ARN EC2_INSTANCE_ID STAGING_BUCKET \
           AWS_ACCOUNT_ID AWS_REGION; do
  if [[ -z "${!VAR}" ]]; then
    echo "MISSING: $VAR"
    FAIL=1
  else
    echo "OK: $VAR=${!VAR}"
  fi
done
[[ "$FAIL" -eq 0 ]] && echo "SETUP OK" || (echo "SETUP FAIL" && exit 1)
```

---

## Task 1 — Verifica infrastruttura e creazione utente di test

**Goal:** verificare che lo stack CloudFormation abbia creato tutte le risorse Cognito (con le configurazioni richieste dai task successivi) e creare un utente di test con cui eseguire il login.

**Steps:**

**Passo 1.1 — Verifica le risorse Cognito**

Il User Pool è l'Identity Provider: gestisce il registro utenti e l'autenticazione. L'Identity Pool è il federation broker: scambia i JWT con credenziali AWS temporanee. Verifichiamo che l'App Client abbia abilitato sia il flusso `code` (per il frontend nel Task 2) sia il flusso `USER_PASSWORD_AUTH` (usato dai success criterion CLI di Task 3 e 4).

```bash
# User Pool: deve esistere e auto-verificare l'email
aws cognito-idp describe-user-pool \
  --user-pool-id "$USER_POOL_ID" \
  --region "$AWS_REGION" \
  --query "UserPool.{Name:Name, Status:Status, EmailVerification:AutoVerifiedAttributes}"

# App Client: stampa tutti i campi rilevanti (OAuth + ExplicitAuthFlows + Callback)
aws cognito-idp describe-user-pool-client \
  --user-pool-id "$USER_POOL_ID" \
  --client-id "$USER_POOL_CLIENT_ID" \
  --region "$AWS_REGION" \
  --query "UserPoolClient.{ClientName:ClientName, AllowedOAuthFlows:AllowedOAuthFlows, AllowedOAuthScopes:AllowedOAuthScopes, ExplicitAuthFlows:ExplicitAuthFlows, CallbackURLs:CallbackURLs}"
```

Controlla nell'output che:
- `AllowedOAuthFlows` contenga `code` — necessario per il flusso Authorization Code nel frontend.
- `ExplicitAuthFlows` contenga `ALLOW_USER_PASSWORD_AUTH` — necessario per i success criterion via CLI di Task 3 e 4.
- `CallbackURLs` contenga esattamente `http://localhost:3000/callback` — il frontend deve girare su quella URL/porta o il login fallisce con `redirect_mismatch`.

```bash
# Identity Pool: deve NON consentire identità anonime per il lab
aws cognito-identity describe-identity-pool \
  --identity-pool-id "$IDENTITY_POOL_ID" \
  --region "$AWS_REGION" \
  --query "{Name:IdentityPoolName, AllowUnauthenticated:AllowUnauthenticatedIdentities}"
```

**Passo 1.2 — Verifica la Hosted UI domain**

Il dominio Hosted UI è l'endpoint da cui Cognito serve le pagine di login/signup prebuilt. Usiamo il prefisso esportato dallo stack (`COGNITO_DOMAIN_PREFIX`) per evitare di ricostruire manualmente il naming.

```bash
# Lo Status deve essere ACTIVE
aws cognito-idp describe-user-pool-domain \
  --domain "$COGNITO_DOMAIN_PREFIX" \
  --region "$AWS_REGION" \
  --query "DomainDescription.{Domain:Domain, Status:Status, UserPoolId:UserPoolId}"
```

**Passo 1.3 — Crea un utente di test**

Creiamo l'utente direttamente via Admin API per evitare il flusso di sign-up via email che richiederebbe interazione manuale.

```bash
# Crea l'utente bypassando la verifica email (--message-action SUPPRESS)
aws cognito-idp admin-create-user \
  --user-pool-id "$USER_POOL_ID" \
  --username "labuser@example.com" \
  --temporary-password "LabTemp1!" \
  --message-action SUPPRESS \
  --region "$AWS_REGION"

# Imposta subito la password definitiva (evita il forced-change-password al primo login)
aws cognito-idp admin-set-user-password \
  --user-pool-id "$USER_POOL_ID" \
  --username "labuser@example.com" \
  --password "LabPassword1!" \
  --permanent \
  --region "$AWS_REGION"
```

**Passo 1.4 — Verifica la scoperta OIDC**

Il discovery endpoint espone la configurazione pubblica del User Pool come IdP: issuer, endpoint di autorizzazione/token, URL delle chiavi pubbliche (JWKS). Qualsiasi client OIDC può auto-configurarsi da qui.

```bash
# L'issuer URL segue sempre il pattern: https://cognito-idp.<region>.amazonaws.com/<user-pool-id>
ISSUER_URL="https://cognito-idp.${AWS_REGION}.amazonaws.com/${USER_POOL_ID}"

curl -s "${ISSUER_URL}/.well-known/openid-configuration" | python3 -m json.tool
```

Nota i campi: `authorization_endpoint`, `token_endpoint`, `jwks_uri`. Questi sono gli URL che userai nel Task 2.

```bash
# Recupera le chiavi pubbliche con cui Cognito firma i JWT
JWKS_URI=$(curl -s "${ISSUER_URL}/.well-known/openid-configuration" | python3 -c "import sys,json; print(json.load(sys.stdin)['jwks_uri'])")
curl -s "$JWKS_URI" | python3 -m json.tool
```

Ogni chiave nel JWKS ha un `kid` (Key ID). Il JWT header include il `kid` della chiave usata per la firma — questo è il meccanismo con cui il validatore sa quale chiave pubblica usare per verificare la firma.

**Expected outcome:** User Pool, App Client (con flussi `code` e `ALLOW_USER_PASSWORD_AUTH` abilitati) e Identity Pool esistono; l'utente `labuser@example.com` è in stato CONFIRMED; il discovery endpoint risponde con la configurazione OIDC.

**Success criterion:**

```bash
# (a) utente confermato
STATUS=$(aws cognito-idp admin-get-user \
  --user-pool-id "$USER_POOL_ID" \
  --username "labuser@example.com" \
  --region "$AWS_REGION" \
  --query "UserStatus" --output text)

# (b) App Client espone i flussi attesi (code + ALLOW_USER_PASSWORD_AUTH)
CLIENT_CFG=$(aws cognito-idp describe-user-pool-client \
  --user-pool-id "$USER_POOL_ID" \
  --client-id "$USER_POOL_CLIENT_ID" \
  --region "$AWS_REGION" \
  --query "UserPoolClient.{oauth:AllowedOAuthFlows, auth:ExplicitAuthFlows, cb:CallbackURLs}" \
  --output json)

# (c) Discovery endpoint risponde
ISSUER_URL="https://cognito-idp.${AWS_REGION}.amazonaws.com/${USER_POOL_ID}"
curl -sf "${ISSUER_URL}/.well-known/openid-configuration" >/dev/null

python3 - "$STATUS" "$CLIENT_CFG" <<'PY' && echo "OK" || (echo "FAIL" && exit 1)
import json, sys
status, raw = sys.argv[1], sys.argv[2]
cfg = json.loads(raw)
assert status == "CONFIRMED",                       f"user status {status}"
assert "code" in (cfg.get("oauth") or []),          f"oauth flows {cfg.get('oauth')}"
assert "ALLOW_USER_PASSWORD_AUTH" in (cfg.get("auth") or []), f"auth flows {cfg.get('auth')}"
assert "http://localhost:3000/callback" in (cfg.get("cb") or []), f"callbacks {cfg.get('cb')}"
PY
```

---

## Task 2 — Frontend: client OAuth 2.0 / OIDC in Python Flask

**Goal:** costruire un'applicazione Flask minimale (gira in locale su porta 3000) che esegue il flusso Authorization Code completo contro la Cognito Hosted UI, poi decodifica e analizza i token ricevuti — senza nascondere i meccanismi dietro un SDK.

**Steps:**

**Passo 2.1 — Installa le dipendenze**

Includiamo `boto3` da subito perché lo useremo nel Task 4 per chiamare `cognito-identity` e per usare credenziali temporanee verso S3. Installandolo ora evitiamo di dover riavviare il server Flask più tardi.

```bash
cd ~/cognito-lab/frontend
pip3 install flask requests "PyJWT[crypto]" cryptography python-dotenv boto3
```

**Passo 2.2 — Crea il file di configurazione**

Il frontend ha bisogno degli stessi valori esportati nel Setup. Li carica da un file `.env` locale. Includiamo già `IDENTITY_POOL_ID` e `AWS_ACCOUNT_ID` perché serviranno nel Task 4 — è più pulito definire tutto in un'unica volta.

**File to create:** `~/cognito-lab/frontend/.env`

```bash
cat > ~/cognito-lab/frontend/.env <<EOF
USER_POOL_ID=${USER_POOL_ID}
USER_POOL_CLIENT_ID=${USER_POOL_CLIENT_ID}
IDENTITY_POOL_ID=${IDENTITY_POOL_ID}
COGNITO_DOMAIN=${COGNITO_DOMAIN}
AWS_REGION=${AWS_REGION}
AWS_ACCOUNT_ID=${AWS_ACCOUNT_ID}
BACKEND_URL=${BACKEND_URL}
SECRET_KEY=dev-secret-change-in-prod
EOF
```

**Passo 2.3 — Scrivi l'applicazione frontend**

Includiamo già le route del Task 4 (`/aws-credentials`, `/list-buckets`) nello stesso file: il Task 4 sarà solo navigazione su quelle route. Evitiamo così edit incrementali del file e un riavvio del server a metà lab. La costante `REDIRECT_URI` è hardcoded perché deve corrispondere ESATTAMENTE al `CallbackURL` registrato in Cognito (verificato nel Task 1.1) — se cambi una delle due, l'altra va aggiornata.

**File to create:** `~/cognito-lab/frontend/app.py`

```python
import base64
import json
import os
import secrets

import boto3
import jwt
import requests
from dotenv import load_dotenv
from flask import Flask, redirect, request, session, url_for

load_dotenv()

app = Flask(__name__)
app.secret_key = os.environ["SECRET_KEY"]

DOMAIN           = os.environ["COGNITO_DOMAIN"]
CLIENT_ID        = os.environ["USER_POOL_CLIENT_ID"]
USER_POOL_ID     = os.environ["USER_POOL_ID"]
IDENTITY_POOL_ID = os.environ["IDENTITY_POOL_ID"]
REGION           = os.environ["AWS_REGION"]
AWS_ACCOUNT_ID   = os.environ["AWS_ACCOUNT_ID"]
BACKEND_URL      = os.environ["BACKEND_URL"]
REDIRECT_URI     = "http://localhost:3000/callback"   # deve combaciare con CallbackURL del client Cognito
ISSUER           = f"https://cognito-idp.{REGION}.amazonaws.com/{USER_POOL_ID}"
JWKS_URI         = f"{ISSUER}/.well-known/jwks.json"

# Cache JWKS in memoria — in produzione si aggiorna periodicamente
_jwks_client = jwt.PyJWKClient(JWKS_URI)


def _b64url_decode(s: str) -> dict:
    """Decodifica Base64URL senza verifica — usato solo per display."""
    padding = 4 - len(s) % 4
    padded = s + "=" * (padding % 4)
    return json.loads(base64.urlsafe_b64decode(padded))


def _split_jwt(token: str):
    parts = token.split(".")
    return _b64url_decode(parts[0]), _b64url_decode(parts[1]), parts[2]


@app.get("/")
def index():
    logged_in = "id_token" in session
    if logged_in:
        body = (
            "<p>Sei autenticato. "
            "<a href='/profile'>Profilo</a> | "
            "<a href='/tokens'>Analisi Token</a> | "
            "<a href='/aws-credentials'>Credenziali AWS</a> | "
            "<a href='/logout'>Logout</a></p>"
        )
    else:
        body = "<p><a href='/login'><button>Login con Cognito Hosted UI</button></a></p>"
    return f"<h2>Cognito Identity Pool Lab — Frontend</h2>{body}"


@app.get("/login")
def login():
    # Genera un nonce casuale per proteggere contro CSRF (state parameter)
    state = secrets.token_urlsafe(16)
    session["oauth_state"] = state
    params = (
        f"?response_type=code"
        f"&client_id={CLIENT_ID}"
        f"&redirect_uri={REDIRECT_URI}"
        f"&scope=openid+email+profile"
        f"&state={state}"
    )
    return redirect(f"{DOMAIN}/oauth2/authorize{params}")


@app.get("/callback")
def callback():
    # Verifica che lo state ricevuto corrisponda a quello inviato (anti-CSRF)
    if request.args.get("state") != session.pop("oauth_state", None):
        return "State mismatch — possibile attacco CSRF", 400

    code = request.args.get("code")
    if not code:
        return f"Errore da Cognito: {request.args.get('error_description', 'unknown')}", 400

    # Scambia il code con i token al token endpoint
    resp = requests.post(
        f"{DOMAIN}/oauth2/token",
        data={
            "grant_type": "authorization_code",
            "code": code,
            "redirect_uri": REDIRECT_URI,
            "client_id": CLIENT_ID,
        },
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        timeout=10,
    )
    resp.raise_for_status()
    tokens = resp.json()
    session["id_token"]      = tokens["id_token"]
    session["access_token"]  = tokens["access_token"]
    session["refresh_token"] = tokens.get("refresh_token", "")
    return redirect(url_for("profile"))


@app.get("/profile")
def profile():
    if "id_token" not in session:
        return redirect(url_for("login"))

    # Decodifica e visualizza il JWT localmente
    _, claims, _ = _split_jwt(session["id_token"])

    # Chiedi anche al backend (Resource Provider) chi siamo — dimostra il flusso RP
    backend_resp = {}
    try:
        r = requests.get(
            f"{BACKEND_URL}/whoami",
            headers={"Authorization": f"Bearer {session['access_token']}"},
            timeout=5,
        )
        backend_resp = r.json()
    except Exception as e:
        backend_resp = {"error": str(e)}

    return (
        "<h2>Profilo utente</h2>"
        "<h3>Claim dall'ID token (decodificato in locale)</h3>"
        f"<pre>{json.dumps(claims, indent=2)}</pre>"
        "<h3>Risposta dal Backend (Resource Provider su EC2)</h3>"
        f"<pre>{json.dumps(backend_resp, indent=2)}</pre>"
        "<p><a href='/tokens'>Analisi dettagliata token</a> | "
        "<a href='/aws-credentials'>Credenziali AWS</a> | "
        "<a href='/logout'>Logout</a></p>"
    )


@app.get("/tokens")
def tokens():
    if "id_token" not in session:
        return redirect(url_for("login"))

    results = {}
    for name, tok in [("ID Token", session["id_token"]),
                      ("Access Token", session["access_token"])]:
        header, payload, sig = _split_jwt(tok)

        # Verifica firma con JWKS — PyJWT recupera la chiave pubblica tramite kid
        verified = False
        verify_error = ""
        try:
            signing_key = _jwks_client.get_signing_key_from_jwt(tok)
            jwt.decode(
                tok,
                signing_key.key,
                algorithms=["RS256"],
                audience=CLIENT_ID if name == "ID Token" else None,
                options={"verify_aud": name == "ID Token"},
            )
            verified = True
        except Exception as e:
            verify_error = str(e)

        results[name] = {
            "header": header,
            "payload": payload,
            "signature_preview": sig[:24] + "...",
            "signature_valid": verified,
            "verify_error": verify_error,
            "claim_annotations": {
                "sub":               "Subject — ID univoco dell'utente nel User Pool",
                "iss":               "Issuer — URL del User Pool che ha emesso il token",
                "aud":               "Audience — client_id dell'app che deve consumare il token",
                "exp":               "Expiration — timestamp Unix di scadenza",
                "iat":               "Issued At — timestamp Unix di emissione",
                "token_use":         "'id' per ID token, 'access' per access token",
                "cognito:username":  "Username nel User Pool",
                "email":             "Email dell'utente (solo nell'ID token)",
            },
        }

    return (
        "<h2>Analisi JWT Token</h2>"
        f"<pre>{json.dumps(results, indent=2)}</pre>"
        "<p><a href='/profile'>Torna al profilo</a></p>"
    )


@app.get("/aws-credentials")
def aws_credentials():
    if "id_token" not in session:
        return redirect(url_for("login"))

    id_token = session["id_token"]
    cognito_identity = boto3.client("cognito-identity", region_name=REGION)
    login_key = f"cognito-idp.{REGION}.amazonaws.com/{USER_POOL_ID}"

    # Passo 1: ottieni l'Identity ID (ID univoco per questo utente in questo Identity Pool)
    identity_resp = cognito_identity.get_id(
        AccountId=AWS_ACCOUNT_ID,
        IdentityPoolId=IDENTITY_POOL_ID,
        Logins={login_key: id_token},
    )
    identity_id = identity_resp["IdentityId"]

    # Passo 2: ottieni le credenziali temporanee STS
    creds_resp = cognito_identity.get_credentials_for_identity(
        IdentityId=identity_id,
        Logins={login_key: id_token},
    )
    creds = creds_resp["Credentials"]

    # Passo 3: verifica chi siamo con queste credenziali
    temp_session = boto3.Session(
        aws_access_key_id=creds["AccessKeyId"],
        aws_secret_access_key=creds["SecretKey"],
        aws_session_token=creds["SessionToken"],
        region_name=REGION,
    )
    caller = temp_session.client("sts").get_caller_identity()

    # Salva in sessione per /list-buckets
    session["temp_creds"] = {
        "AccessKeyId":  creds["AccessKeyId"],
        "SecretKey":    creds["SecretKey"],
        "SessionToken": creds["SessionToken"],
        "Expiration":   creds["Expiration"].isoformat(),
    }

    display = {
        "identity_id":           identity_id,
        "access_key_id":         creds["AccessKeyId"],
        "secret_key_preview":    creds["SecretKey"][:4] + "****",
        "session_token_preview": creds["SessionToken"][:20] + "...",
        "expires_at":            creds["Expiration"].isoformat(),
        "assumed_role_arn":      caller["Arn"],
        "account":               caller["Account"],
    }
    return (
        "<h2>Credenziali AWS Temporanee (via Identity Pool)</h2>"
        f"<pre>{json.dumps(display, indent=2)}</pre>"
        "<p><a href='/list-buckets'>Chiama S3 con queste credenziali</a> | "
        "<a href='/profile'>Profilo</a></p>"
    )


@app.get("/list-buckets")
def list_buckets():
    if "temp_creds" not in session:
        return redirect(url_for("aws_credentials"))

    c = session["temp_creds"]
    temp_session = boto3.Session(
        aws_access_key_id=c["AccessKeyId"],
        aws_secret_access_key=c["SecretKey"],
        aws_session_token=c["SessionToken"],
        region_name=REGION,
    )

    try:
        buckets = temp_session.client("s3").list_buckets()
        result = {
            "success": True,
            "bucket_count": len(buckets["Buckets"]),
            "buckets": [b["Name"] for b in buckets["Buckets"]],
            "note": "Chiamata autorizzata: il ruolo IAM dell'Identity Pool include s3:ListAllMyBuckets",
        }
    except Exception as e:
        result = {"success": False, "error": str(e)}

    return (
        "<h2>S3 ListAllMyBuckets con credenziali temporanee</h2>"
        f"<pre>{json.dumps(result, indent=2)}</pre>"
        "<p><a href='/aws-credentials'>Torna alle credenziali</a></p>"
    )


@app.get("/logout")
def logout():
    session.clear()
    logout_url = (
        f"{DOMAIN}/logout"
        f"?client_id={CLIENT_ID}"
        f"&logout_uri=http://localhost:3000/"
    )
    return redirect(logout_url)


if __name__ == "__main__":
    app.run(port=3000, debug=True)
```

**Passo 2.4 — Avvia il frontend in background**

`python3 app.py` in foreground blocca il terminale. Lo avviamo in background per poter eseguire i success criterion nello stesso shell. Il PID viene salvato in un file per poterlo terminare al cleanup.

```bash
cd ~/cognito-lab/frontend
# Avvia il server in background; log su file
nohup python3 app.py > frontend.log 2>&1 &
echo $! > frontend.pid

# Attendi che la porta 3000 sia in ascolto (max ~10s)
for i in $(seq 1 20); do
  curl -sf -o /dev/null http://localhost:3000/ && break
  sleep 0.5
done
echo "Frontend PID: $(cat frontend.pid)"
```

Apri ora il browser su `http://localhost:3000` e completa il login con `labuser@example.com` / `LabPassword1!`. Esamina le pagine `/profile` e `/tokens` per vedere claim e firma verificata.

**Expected outcome:** il server risponde su `/` (200) e su `/login` (302 verso `https://cognito-lab-<account>.auth.eu-west-1.amazoncognito.com/oauth2/authorize?...`). Dopo il login via browser, `/tokens` mostra `"signature_valid": true` per entrambi i token e `/profile` mostra le claim dell'ID token più la risposta del backend (Task 3).

**Success criterion:**

```bash
# (a) root risponde 200 con il titolo del lab
curl -sf http://localhost:3000/ | grep -q "Cognito Identity Pool Lab"

# (b) /login risponde 302 con Location verso il dominio Cognito Hosted UI
LOCATION=$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' http://localhost:3000/login)
echo "/login -> $LOCATION"
python3 - "$LOCATION" "$COGNITO_DOMAIN" <<'PY' && echo "OK" || (echo "FAIL" && exit 1)
import sys
loc, domain = sys.argv[1], sys.argv[2]
code, _, url = loc.partition(" ")
assert code == "302",                       f"expected 302, got {code}"
assert url.startswith(f"{domain}/oauth2/authorize?"), f"bad redirect: {url}"
assert "response_type=code" in url,         "missing response_type=code"
assert "scope=openid+email+profile" in url, "missing scope"
PY
```

---

## Task 3 — Backend: Flask API su EC2 con validazione JWT

**Goal:** scrivere un'API Flask su EC2 che riceve il JWT access token nell'header `Authorization: Bearer`, lo valida esplicitamente passo per passo, e restituisce l'analisi completa del token — dimostrando il ruolo del Resource Provider.

**Steps:**

**Passo 3.1 — Scrivi il backend in locale**

Scriviamo prima il file sulla macchina locale, poi lo trasferiamo sull'EC2 via il bucket S3 di staging già creato dallo stack. Questo evita di incollare ~150 righe di Python in una sessione SSM interattiva (errori di indentazione, problemi di whitespace). Il backend NON usa un middleware di autenticazione: ogni passo della validazione è esplicito, per rendere visibile l'intero processo.

**File to create:** `~/cognito-lab/backend/app.py`

```python
import base64
import json
import os
import time
from datetime import datetime, timezone

import jwt
import requests
from flask import Flask, jsonify, request

app = Flask(__name__)

USER_POOL_ID = os.environ["USER_POOL_ID"]
CLIENT_ID    = os.environ["USER_POOL_CLIENT_ID"]
REGION       = os.environ.get("AWS_REGION", "eu-west-1")
ISSUER       = f"https://cognito-idp.{REGION}.amazonaws.com/{USER_POOL_ID}"
JWKS_URI     = f"{ISSUER}/.well-known/jwks.json"

_jwks_client = jwt.PyJWKClient(JWKS_URI)


def _b64url_decode(s: str) -> dict:
    padding = 4 - len(s) % 4
    padded = s + "=" * (padding % 4)
    return json.loads(base64.urlsafe_b64decode(padded))


def _validate_token(token: str):
    """
    Valida il JWT in 5 passi espliciti.
    Ritorna (header, claims, raw_payload) oppure solleva ValueError.
    """
    parts = token.split(".")
    if len(parts) != 3:
        raise ValueError("JWT malformato: deve avere 3 parti separate da '.'")

    # Passo 1 — Decodifica header (senza verifica) per leggere kid e alg
    header = _b64url_decode(parts[0])
    if header.get("alg") != "RS256":
        raise ValueError(f"Algoritmo non supportato: {header.get('alg')} — atteso RS256")

    # Passo 2 — Recupera la chiave pubblica dal JWKS usando il kid
    try:
        signing_key = _jwks_client.get_signing_key_from_jwt(token)
    except Exception as e:
        raise ValueError(f"kid '{header.get('kid')}' non trovato nel JWKS: {e}")

    # Passo 3 — Verifica firma e decodifica il payload
    try:
        claims = jwt.decode(
            token,
            signing_key.key,
            algorithms=["RS256"],
            options={"verify_aud": False},  # verifichiamo aud manualmente al passo 4
        )
    except jwt.ExpiredSignatureError:
        raise ValueError("Token scaduto (exp nel passato)")
    except jwt.InvalidSignatureError:
        raise ValueError("Firma JWT non valida — token manomesso o chiave errata")

    # Passo 4 — Verifica iss (deve corrispondere al nostro User Pool)
    if claims.get("iss") != ISSUER:
        raise ValueError(f"Issuer non valido: {claims.get('iss')} — atteso {ISSUER}")

    # Passo 5 — Verifica token_use (accettiamo sia 'access' che 'id')
    token_use = claims.get("token_use")
    if token_use not in ("access", "id"):
        raise ValueError(f"token_use non valido: {token_use}")

    return header, claims, parts[1]


def _auth_token() -> str:
    """Estrae il Bearer token dall'header Authorization."""
    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        from flask import abort
        abort(401, description="Header Authorization mancante o non Bearer")
    return auth[7:]


@app.get("/health")
def health():
    return jsonify(status="ok")


@app.get("/whoami")
def whoami():
    """Valida il token e restituisce le claim — risposta concisa per il frontend."""
    token = _auth_token()
    try:
        _, claims, _ = _validate_token(token)
    except ValueError as e:
        return jsonify(error=str(e)), 401
    return jsonify(claims)


@app.get("/introspect")
def introspect():
    """Restituisce l'analisi completa del token: header, payload, validità, claim annotate."""
    token = _auth_token()
    parts = token.split(".")
    raw_header  = parts[0] if len(parts) > 0 else ""
    raw_payload = parts[1] if len(parts) > 1 else ""
    raw_sig     = parts[2] if len(parts) > 2 else ""

    try:
        header, claims, _ = _validate_token(token)
        sig_valid = True
        sig_error = None
    except ValueError as e:
        sig_valid = False
        sig_error = str(e)
        # Decodifica comunque header e payload per mostrare cosa c'è dentro
        try:
            header = _b64url_decode(raw_header)
            claims = _b64url_decode(raw_payload)
        except Exception:
            header, claims = {}, {}

    exp_ts = claims.get("exp", 0)
    exp_dt = datetime.fromtimestamp(exp_ts, tz=timezone.utc).isoformat() if exp_ts else None

    return jsonify({
        "token_header":    header,
        "token_claims":    claims,
        "token_type":      claims.get("token_use", "unknown"),
        "issuer":          claims.get("iss"),
        "audience":        claims.get("aud") or claims.get("client_id"),
        "expires_at":      exp_dt,
        "is_expired":      exp_ts < time.time() if exp_ts else True,
        "signature_valid": sig_valid,
        "signature_error": sig_error,
        "raw_header":      raw_header,
        "raw_payload":     raw_payload,
        "raw_signature":   raw_sig[:20] + "..." if raw_sig else "",
        "claim_annotations": {
            "sub":              "ID univoco e immutabile dell'utente nel User Pool",
            "iss":              "Issuer: URL del User Pool che ha emesso il token",
            "exp":              "Expiration: Unix timestamp di scadenza (access token: 1h, ID token: 1h)",
            "iat":              "Issued At: Unix timestamp di emissione",
            "token_use":        "'access' per operare sulle API, 'id' per l'identità dell'utente",
            "client_id":        "App client che ha richiesto il token (solo nell'access token)",
            "cognito:username": "Username nel User Pool (può differire da sub)",
            "scope":            "Scope OAuth 2.0 concessi (solo nell'access token)",
        },
    })


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
```

**Passo 3.2 — Carica il file sul bucket di staging**

Il bucket S3 è già creato dallo stack (`STAGING_BUCKET`) e il ruolo dell'EC2 ha il permesso di leggerlo. Carichiamo il file lì.

```bash
aws s3 cp ~/cognito-lab/backend/app.py "s3://${STAGING_BUCKET}/backend/app.py" \
  --region "$AWS_REGION"

# Verifica
aws s3 ls "s3://${STAGING_BUCKET}/backend/" --region "$AWS_REGION"
```

**Passo 3.3 — Scarica il file sull'EC2 via SSM Run Command**

Usiamo `aws ssm send-command` (non `start-session`) perché è non-interattivo, ritorna un command-id e si può attendere il completamento via `aws ssm wait` — adatto sia a esecuzione manuale che automatizzata. Il comando scarica il file da S3, fixa i permessi e riavvia il servizio systemd già installato dal UserData.

```bash
CMD_ID=$(aws ssm send-command \
  --instance-ids "$EC2_INSTANCE_ID" \
  --document-name "AWS-RunShellScript" \
  --region "$AWS_REGION" \
  --comment "Deploy backend app.py" \
  --parameters "commands=[\
\"aws s3 cp s3://${STAGING_BUCKET}/backend/app.py /opt/backend/app.py --region ${AWS_REGION}\",\
\"chown ec2-user:ec2-user /opt/backend/app.py\",\
\"systemctl restart backend.service\",\
\"sleep 2\",\
\"systemctl is-active backend.service\"]" \
  --query "Command.CommandId" --output text)

echo "SSM Command ID: $CMD_ID"

# Attendi che il command completi (max ~30s)
aws ssm wait command-executed \
  --command-id "$CMD_ID" \
  --instance-id "$EC2_INSTANCE_ID" \
  --region "$AWS_REGION"

# Mostra lo stdout del comando
aws ssm get-command-invocation \
  --command-id "$CMD_ID" \
  --instance-id "$EC2_INSTANCE_ID" \
  --region "$AWS_REGION" \
  --query "{Status:Status, Stdout:StandardOutputContent, Stderr:StandardErrorContent}"
```

Lo `Status` deve essere `Success` e `Stdout` deve finire con `active`. In caso di errore, ispeziona `Stderr`.

**Passo 3.4 — Testa l'API dal tuo terminale locale**

Per testare via CLI senza passare dal browser, otteniamo un access token via `USER_PASSWORD_AUTH` (flusso abilitato sull'App Client nel Task 1.1).

```bash
# Ottieni un access token via Cognito (flusso password diretto, solo per test CLI)
TOKEN_RESP=$(aws cognito-idp initiate-auth \
  --auth-flow USER_PASSWORD_AUTH \
  --auth-parameters USERNAME=labuser@example.com,PASSWORD=LabPassword1! \
  --client-id "$USER_POOL_CLIENT_ID" \
  --region "$AWS_REGION")

ACCESS_TOKEN=$(echo "$TOKEN_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['AuthenticationResult']['AccessToken'])")

# Health check (nessun auth richiesto)
curl -sf "$BACKEND_URL/health"

# Whoami: claim minime
curl -sf -H "Authorization: Bearer $ACCESS_TOKEN" "$BACKEND_URL/whoami" | python3 -m json.tool

# Analisi completa
curl -sf -H "Authorization: Bearer $ACCESS_TOKEN" "$BACKEND_URL/introspect" | python3 -m json.tool
```

**Expected outcome:** `/introspect` ritorna `"signature_valid": true`, mostra header + payload decodificati con `token_use: "access"`, e le annotazioni spiegano ogni claim. `/whoami` con header `Authorization` mancante ritorna 401.

**Success criterion:**

```bash
# (a) /health risponde senza autenticazione
curl -sf "$BACKEND_URL/health" | grep -q '"status"' || (echo "FAIL: health" && exit 1)

# (b) /whoami senza token ritorna 401
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BACKEND_URL/whoami")
[[ "$CODE" == "401" ]] || (echo "FAIL: whoami no-auth code=$CODE" && exit 1)

# (c) /introspect con token valido ritorna signature_valid=true
ACCESS_TOKEN=$(aws cognito-idp initiate-auth \
  --auth-flow USER_PASSWORD_AUTH \
  --auth-parameters USERNAME=labuser@example.com,PASSWORD=LabPassword1! \
  --client-id "$USER_POOL_CLIENT_ID" --region "$AWS_REGION" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['AuthenticationResult']['AccessToken'])")

curl -sf -H "Authorization: Bearer $ACCESS_TOKEN" "$BACKEND_URL/introspect" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); assert d['signature_valid'] is True, 'sig invalid'; assert d['token_type']=='access', f'wrong token_use: {d[\"token_type\"]}'" \
  && echo "OK" || (echo "FAIL" && exit 1)
```

---

## Task 4 — Autorizzazione IAM tramite Identity Pool

**Goal:** usare il Cognito Identity Pool per scambiare l'ID token con credenziali AWS temporanee (STS), poi chiamare un'API AWS con quelle credenziali — dimostrando l'autorizzazione IAM basata sull'identità federata.

**Steps:**

**Passo 4.1 — Comprendi il flusso Identity Pool**

Il flusso è distinto dal login OAuth 2.0: non stai autenticando l'utente (già fatto nel Task 2), stai chiedendo a AWS di *fidarsi* del token Cognito e di emettere credenziali IAM temporanee.

```
ID Token (Cognito) → Identity Pool → STS AssumeRoleWithWebIdentity → AccessKeyId + SecretKey + SessionToken
                                          ↓
                               Il ruolo IAM definisce cosa puoi fare con queste credenziali
```

Il ruolo `AuthenticatedRole` creato dallo stack ha una policy minimale: `s3:ListAllMyBuckets` + `sts:GetCallerIdentity`. È deliberatamente ristretto, così puoi osservare che `s3:DeleteBucket` viene rifiutato (vedi Passo 4.3).

**Passo 4.2 — Naviga le route già implementate nel frontend**

Le route `/aws-credentials` e `/list-buckets` sono già nel file `app.py` scritto nel Task 2 (Passo 2.3). Il server Flask è già in esecuzione: ricarica semplicemente la pagina, oppure naviga direttamente.

Nel browser:

1. `http://localhost:3000/aws-credentials` — Vedrai l'Identity ID, il ruolo IAM assunto (deve coincidere con `$AUTHENTICATED_ROLE_ARN`), e una preview delle credenziali temporanee.
2. `http://localhost:3000/list-buckets` — Esegue `s3:ListAllMyBuckets` con le credenziali temporanee. Anche se l'account non ha bucket, la risposta `success: true` dimostra che la chiamata è stata autorizzata.

**Passo 4.3 — Checkpoint: dimostra il limite della policy**

Le credenziali ottenute non hanno il permesso `s3:DeleteBucket`. Verifichiamolo dal terminale locale, ottenendo le stesse credenziali via CLI:

```bash
# Ottieni un ID token (necessario per Identity Pool, non un access token)
ID_TOKEN=$(aws cognito-idp initiate-auth \
  --auth-flow USER_PASSWORD_AUTH \
  --auth-parameters USERNAME=labuser@example.com,PASSWORD=LabPassword1! \
  --client-id "$USER_POOL_CLIENT_ID" --region "$AWS_REGION" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['AuthenticationResult']['IdToken'])")

# Scambia l'ID token con un Identity ID
IDENTITY_ID=$(aws cognito-identity get-id \
  --account-id "$AWS_ACCOUNT_ID" \
  --identity-pool-id "$IDENTITY_POOL_ID" \
  --logins "cognito-idp.${AWS_REGION}.amazonaws.com/${USER_POOL_ID}=${ID_TOKEN}" \
  --region "$AWS_REGION" \
  --query IdentityId --output text)

# Ottieni le credenziali temporanee
CREDS=$(aws cognito-identity get-credentials-for-identity \
  --identity-id "$IDENTITY_ID" \
  --logins "cognito-idp.${AWS_REGION}.amazonaws.com/${USER_POOL_ID}=${ID_TOKEN}" \
  --region "$AWS_REGION")

# Esporta nel formato che la CLI consuma direttamente
export AWS_ACCESS_KEY_ID_TMP=$(echo "$CREDS" | python3 -c "import sys,json; print(json.load(sys.stdin)['Credentials']['AccessKeyId'])")
export AWS_SECRET_ACCESS_KEY_TMP=$(echo "$CREDS" | python3 -c "import sys,json; print(json.load(sys.stdin)['Credentials']['SecretKey'])")
export AWS_SESSION_TOKEN_TMP=$(echo "$CREDS" | python3 -c "import sys,json; print(json.load(sys.stdin)['Credentials']['SessionToken'])")

# Funziona: la policy include s3:ListAllMyBuckets
AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID_TMP" \
AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY_TMP" \
AWS_SESSION_TOKEN="$AWS_SESSION_TOKEN_TMP" \
  aws s3api list-buckets --region "$AWS_REGION" --query "Buckets[].Name" --output table

# Fallisce: la policy NON include s3:DeleteBucket → AccessDenied atteso
AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID_TMP" \
AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY_TMP" \
AWS_SESSION_TOKEN="$AWS_SESSION_TOKEN_TMP" \
  aws s3api delete-bucket --bucket non-esistente-2026 --region "$AWS_REGION" 2>&1 || true
```

Rispondi mentalmente (o annota) le seguenti domande:

1. **Qual è la differenza tra il Cognito access token e l'AWS AccessKeyId temporaneo?**
   Il Cognito access token autentica l'utente verso le API Cognito o un tuo backend custom (che lo valida via JWKS). L'AccessKeyId è una credenziale IAM nativa: autorizza chiamate dirette ad AWS senza un backend intermediario, ed è soggetta alle policy IAM del ruolo assunto.

2. **Perché l'Identity Pool richiede l'ID token (non l'access token) nella mappa `Logins`?**
   L'ID token contiene le claim di identità (`sub`, `email`) — è progettato per essere presentato come prova di identità. L'access token contiene scope OAuth 2.0 ed è destinato all'autorizzazione di chiamate API, non all'identificazione del soggetto.

3. **Cosa succede con `s3:DeleteBucket`?**
   `AccessDenied`: il trust policy del ruolo è soddisfatto, ma la policy *inline* del ruolo non include `s3:DeleteBucket`. Le credenziali temporanee ereditano esattamente i permessi del ruolo assunto.

**Expected outcome:** `/aws-credentials` mostra l'ARN del ruolo assunto coincidente con `$AUTHENTICATED_ROLE_ARN`. `/list-buckets` ritorna `success: true`. Da CLI, `list-buckets` funziona e `delete-bucket` ritorna `AccessDenied`.

**Success criterion:**

```bash
# Ottieni credenziali e verifica che il ruolo assunto coincida con AUTHENTICATED_ROLE_ARN dello stack
ID_TOKEN=$(aws cognito-idp initiate-auth \
  --auth-flow USER_PASSWORD_AUTH \
  --auth-parameters USERNAME=labuser@example.com,PASSWORD=LabPassword1! \
  --client-id "$USER_POOL_CLIENT_ID" --region "$AWS_REGION" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['AuthenticationResult']['IdToken'])")

IDENTITY_ID=$(aws cognito-identity get-id \
  --account-id "$AWS_ACCOUNT_ID" \
  --identity-pool-id "$IDENTITY_POOL_ID" \
  --logins "cognito-idp.${AWS_REGION}.amazonaws.com/${USER_POOL_ID}=${ID_TOKEN}" \
  --region "$AWS_REGION" \
  --query IdentityId --output text)

CREDS=$(aws cognito-identity get-credentials-for-identity \
  --identity-id "$IDENTITY_ID" \
  --logins "cognito-idp.${AWS_REGION}.amazonaws.com/${USER_POOL_ID}=${ID_TOKEN}" \
  --region "$AWS_REGION")

AK=$(echo "$CREDS" | python3 -c "import sys,json; print(json.load(sys.stdin)['Credentials']['AccessKeyId'])")
SK=$(echo "$CREDS" | python3 -c "import sys,json; print(json.load(sys.stdin)['Credentials']['SecretKey'])")
ST=$(echo "$CREDS" | python3 -c "import sys,json; print(json.load(sys.stdin)['Credentials']['SessionToken'])")

CALLER_ARN=$(AWS_ACCESS_KEY_ID="$AK" AWS_SECRET_ACCESS_KEY="$SK" AWS_SESSION_TOKEN="$ST" \
  aws sts get-caller-identity --region "$AWS_REGION" --query Arn --output text)

# Il ruolo nell'ARN della sessione assumed-role deve coincidere col nome del ruolo dello stack
# AUTHENTICATED_ROLE_ARN = arn:aws:iam::<acct>:role/<role-name>
# CALLER_ARN             = arn:aws:sts::<acct>:assumed-role/<role-name>/<session>
ROLE_NAME=$(basename "$AUTHENTICATED_ROLE_ARN")

python3 - "$CALLER_ARN" "$ROLE_NAME" <<'PY' && echo "OK (role match)" || (echo "FAIL" && exit 1)
import sys
arn, role = sys.argv[1], sys.argv[2]
assert arn.startswith("arn:aws:sts::"),      f"non STS ARN: {arn}"
assert "assumed-role" in arn,                f"non assumed-role: {arn}"
assert f"assumed-role/{role}/" in arn,       f"role mismatch: expected {role} in {arn}"
PY

# Verifica negativa: DeleteBucket DEVE fallire con AccessDenied
OUT=$(AWS_ACCESS_KEY_ID="$AK" AWS_SECRET_ACCESS_KEY="$SK" AWS_SESSION_TOKEN="$ST" \
  aws s3api delete-bucket --bucket non-esistente-2026 --region "$AWS_REGION" 2>&1 || true)
echo "$OUT" | grep -qi "AccessDenied" && echo "OK (delete denied)" || (echo "FAIL: expected AccessDenied, got: $OUT" && exit 1)
```

---

## Cleanup

Rimuovi tutte le risorse create dal lab per evitare costi.

```bash
export STACK_NAME="recube-lab-cognito-identity-pool"
export AWS_REGION="eu-west-1"

# 0 — Ferma il frontend locale (se ancora in esecuzione)
if [[ -f ~/cognito-lab/frontend/frontend.pid ]]; then
  kill "$(cat ~/cognito-lab/frontend/frontend.pid)" 2>/dev/null || true
  rm -f ~/cognito-lab/frontend/frontend.pid
fi

# 1 — Elimina l'utente di test (necessario solo se è stato creato in Task 1)
USER_POOL_ID=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" --region "$AWS_REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='UserPoolId'].OutputValue" \
  --output text 2>/dev/null)

if [[ -n "$USER_POOL_ID" ]]; then
  aws cognito-idp admin-delete-user \
    --user-pool-id "$USER_POOL_ID" \
    --username "labuser@example.com" \
    --region "$AWS_REGION" 2>/dev/null || true
fi

# 2 — Elimina la Hosted UI domain (deve precedere il delete del User Pool)
DOMAIN_PREFIX=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" --region "$AWS_REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='CognitoDomainPrefix'].OutputValue" \
  --output text 2>/dev/null)

if [[ -n "$DOMAIN_PREFIX" && -n "$USER_POOL_ID" ]]; then
  aws cognito-idp delete-user-pool-domain \
    --domain "$DOMAIN_PREFIX" \
    --user-pool-id "$USER_POOL_ID" \
    --region "$AWS_REGION" 2>/dev/null || true
fi

# 3 — Svuota il bucket di staging (CFN delete fallisce su bucket non vuoti)
STAGING_BUCKET=$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" --region "$AWS_REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='StagingBucketName'].OutputValue" \
  --output text 2>/dev/null)

if [[ -n "$STAGING_BUCKET" ]]; then
  aws s3 rm "s3://${STAGING_BUCKET}" --recursive --region "$AWS_REGION" 2>/dev/null || true
fi

# 4 — Elimina lo stack CloudFormation (rimuove EC2, Identity Pool, User Pool, IAM roles, SG, S3)
aws cloudformation delete-stack \
  --stack-name "$STACK_NAME" \
  --region "$AWS_REGION"

echo "Stack delete avviato. Attendo il completamento (~3 min)..."
aws cloudformation wait stack-delete-complete \
  --stack-name "$STACK_NAME" \
  --region "$AWS_REGION"

# 5 — Verifica che non rimangano risorse taggiate
REMAIN=$(aws resourcegroupstaggingapi get-resources \
  --tag-filters Key=lab,Values=cognito-identity-pool \
  --region "$AWS_REGION" \
  --query 'ResourceTagMappingList[].ResourceARN' \
  --output text)
[[ -z "$REMAIN" ]] && echo "Nessuna risorsa residua." || echo "ATTENZIONE: risorse residue: $REMAIN"

# 6 — Rimuovi i file locali
rm -rf ~/cognito-lab
```

---

## Troubleshooting

**`redirect_mismatch` dalla Cognito Hosted UI**
Il `REDIRECT_URI` nel frontend deve corrispondere ESATTAMENTE al `CallbackURL` registrato nell'App Client (`http://localhost:3000/callback`, senza trailing slash). Verifica con `aws cognito-idp describe-user-pool-client ... --query "UserPoolClient.CallbackURLs"`.

**`State mismatch` sulla callback**
Il cookie di sessione Flask non persiste tra richieste. Assicurati che `SECRET_KEY` sia impostato nel `.env` e che il browser accetti i cookie da `localhost`. Se hai cambiato browser tra `/login` e `/callback`, il cookie non viene inviato.

**`Connection refused` su `$BACKEND_URL`**
Verifica che il Security Group dell'EC2 permetta la porta 5000 dal tuo IP, che il servizio `backend.service` sia in stato `active (running)` (`aws ssm send-command ... systemctl status backend.service`), e che l'EC2 abbia un IP pubblico (il SubnetId passato come parametro deve essere una subnet pubblica con `MapPublicIpOnLaunch=true`).

**`NotAuthorizedException: User password combination is incorrect` su `initiate-auth`**
La password è effettivamente sbagliata (controlla che il passo `admin-set-user-password` sia andato a buon fine) oppure il flow `ALLOW_USER_PASSWORD_AUTH` non è nei `ExplicitAuthFlows` del client (verificato nel Task 1.1).

**`ExpiredSignatureError` nella validazione JWT**
L'access token scade dopo 1 ora. Rieffettua il login nel frontend o riesegui `initiate-auth` dalla CLI per ottenere token freschi.

**`NotAuthorizedException` su `get_credentials_for_identity`**
Il token passato nel `Logins` map deve essere l'**ID token**, non l'access token. Controlla che nel codice (route `/aws-credentials`) sia usato `session["id_token"]`.

**Il backend risponde `401` con `kid non trovato nel JWKS`**
I token sono stati emessi prima che il JWKS venisse aggiornato (raro) oppure stai inviando un token di un User Pool diverso. Verifica che la variabile `USER_POOL_ID` impostata nel service unit del backend coincida con quella esportata dallo stack (`systemctl cat backend.service` via SSM).

**`ModuleNotFoundError: No module named 'boto3'` al riavvio del frontend**
La dipendenza `boto3` non è stata installata nel Passo 2.1. Esegui `pip3 install boto3` e riavvia.

**SSM `send-command` resta in `InProgress` o ritorna `TimedOut`**
L'agente SSM sull'EC2 non è attivo. Controlla via console (EC2 → Fleet Manager) che l'istanza sia "Managed". Se non lo è, attendi 1-2 minuti dal launch e riprova: l'agente richiede tempo per registrarsi.

---
lab_id: cryptography-openssl
version: 1
status: draft
generated_at: 2026-06-22T09:30:29.642909+00:00
based_on_evaluation: null
---

# Cryptography Fundamentals with OpenSSL and AWS S3

## Learning Objectives

- Usare la CLI di OpenSSL per generare entropia, calcolare digest crittografici e ispezionare certificati X.509
- Cifrare e decifrare file con cifratura simmetrica AES-256-CBC usando una passphrase derivata via PBKDF2
- Cifrare e decifrare file con cifratura asimmetrica RSA-2048, e implementare lo schema ibrido RSA+AES per file di grandi dimensioni
- Provisionare via AWS CLI un bucket S3 e un utente IAM con policy a privilegio minimo (`s3:PutObject` / `s3:GetObject`)
- Eseguire la Client-Side Encryption (CSE) di un oggetto S3 con OpenSSL e verificare l'illeggibilità del ciphertext lato server
- Creare una Customer Managed KMS Key (CMK), caricare oggetti con SSE-KMS e osservare il comportamento di Access Denied quando la chiave viene disabilitata
- Generare un certificato X.509 self-signed con SAN e installarlo su un'istanza EC2 con nginx per servire HTTPS

## Prerequisites

### Tools required (with exact versions)

Verifica la presenza dei tool richiesti. Ogni comando deve ritornare exit 0.

```bash
openssl version           # OpenSSL >= 1.1
aws --version             # AWS CLI v2.x
curl --version            # curl (per verificare HTTPS)
ssh -V                    # OpenSSH client (per accedere a EC2)
jq --version              # jq (parsing JSON delle risposte AWS)
```

### Knowledge assumed

- Familiarità con la shell bash (variabili d'ambiente, redirezione, heredoc)
- Concetti base di crittografia simmetrica vs asimmetrica (a livello di vocabolario)
- Conoscenza di base degli oggetti AWS: S3 bucket, IAM user/policy, EC2 instance, security group
- Notazione ARN (Amazon Resource Name) e formato JSON delle IAM policy

## Setup

Il setup configura le variabili d'ambiente, verifica l'accesso AWS e prepara la directory di lavoro. Tutti i task del lab assumono che queste variabili siano esportate nella shell corrente.

```bash
# Configurazione AWS: il profilo `lab-provisioned` è quello creato dal factory
# con credenziali admin sull'account dedicato al lab.
export AWS_PROFILE=lab-provisioned
export AWS_REGION=eu-west-1
export AWS_DEFAULT_REGION=eu-west-1
```

```bash
# Prefisso personale: viene usato per i nomi globalmente unici (bucket S3, KMS alias).
# Usiamo whoami normalizzato + epoch corto per garantire unicità anche in aula condivisa.
export USER_PREFIX="$(whoami | tr 'A-Z' 'a-z' | tr -cd 'a-z0-9' | cut -c1-12)-$(date +%s | tail -c 6)"
echo "USER_PREFIX=$USER_PREFIX"
```

```bash
# Directory di lavoro del lab. Tutti i file generati (chiavi, certificati, ciphertext)
# vivranno qui per facilitare il cleanup finale.
export LAB_DIR="$HOME/crypto-lab"
mkdir -p "$LAB_DIR"
cd "$LAB_DIR"
```

```bash
# AWS Account ID: serve per costruire ARN espliciti nelle policy IAM/KMS.
export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "AWS_ACCOUNT_ID=$AWS_ACCOUNT_ID"
```

```bash
# IP pubblico della workstation: serve per restringere le regole SG di SSH al solo IP del learner.
export MY_IP=$(curl -s https://checkip.amazonaws.com)
echo "MY_IP=$MY_IP"
```

### Verifica setup

Il comando seguente fallisce (exit != 0) se uno qualsiasi dei prerequisiti non è soddisfatto.

```bash
test -d "$LAB_DIR" \
  && test -n "$USER_PREFIX" \
  && test -n "$AWS_ACCOUNT_ID" \
  && test -n "$MY_IP" \
  && aws sts get-caller-identity > /dev/null \
  && openssl version > /dev/null \
  && echo "Setup OK"
```

---

## Task 1 — OpenSSL Basics

**Goal:** Familiarizzare con la CLI di OpenSSL: generare bytes casuali con qualità crittografica, calcolare digest e ispezionare un certificato esistente.

**Steps:**

```bash
# Generiamo 32 bytes di entropia in formato base64. /dev/urandom alimenta il generatore
# di OpenSSL: questi 32 bytes (256 bit) sono la dimensione tipica di una chiave AES-256.
openssl rand -base64 32 > random_key.b64
cat random_key.b64
```

```bash
# Generiamo 16 bytes in formato esadecimale: dimensione standard di un IV per AES-CBC
# (block size = 128 bit). Un IV non deve essere segreto, ma DEVE essere unico per chiave.
openssl rand -hex 16 > random_iv.hex
cat random_iv.hex
```

**File to create:** `hello.txt`

```text
Hello, cryptography world.
```

```bash
# Calcoliamo lo SHA-256 del file di test. SHA-256 è il digest di default per la maggior parte
# delle applicazioni moderne (TLS, blockchain, integrità). Output: 64 caratteri esadecimali = 256 bit.
openssl dgst -sha256 hello.txt | tee hello.sha256
```

```bash
# Calcoliamo anche MD5 per mostrare l'output più corto (128 bit = 32 hex chars).
# MD5 è considerato rotto per uso security-sensitive ma è ancora usato per checksum non-criptografici.
openssl dgst -md5 hello.txt | tee hello.md5
```

```bash
# Ispezioniamo un certificato presente sul filesystem: prendiamo il bundle di CA root
# del sistema (Amazon Linux/Linux/macOS hanno path diversi; usiamo openssl s_client per
# pescare un cert reale da un server pubblico).
openssl s_client -connect www.amazon.com:443 -servername www.amazon.com < /dev/null 2>/dev/null \
  | openssl x509 -noout -subject -issuer -dates > amazon_cert_info.txt
cat amazon_cert_info.txt
```

**Expected outcome:**
- `random_key.b64` contiene una stringa base64 di 44 caratteri (32 bytes encoded)
- `random_iv.hex` contiene 32 caratteri esadecimali
- `hello.sha256` contiene un digest che inizia con la firma SHA256
- `amazon_cert_info.txt` mostra subject, issuer, validità del certificato di amazon.com

**Success criterion:**

```bash
test -s random_key.b64 \
  && test "$(wc -c < random_iv.hex | tr -d ' ')" -ge 32 \
  && grep -q "SHA2-256\|SHA256" hello.sha256 \
  && grep -q "subject=" amazon_cert_info.txt
```

---

## Task 2 — Symmetric Encryption with AES-256-CBC

**Goal:** Cifrare e decifrare un file usando AES-256-CBC con chiave derivata da passphrase via PBKDF2, e verificare che il ciphertext sia indistinguibile da rumore.

**Steps:**

**File to create:** `secret.txt`

```text
This is a confidential message that must be protected with symmetric encryption.
Only those who know the shared passphrase can read it.
Line three: lorem ipsum dolor sit amet.
```

```bash
# Cifriamo con AES-256-CBC. Le opzioni rilevanti:
#   -salt    : aggiunge un salt random nell'header → due cifrature dello stesso plaintext
#              con la stessa passphrase producono ciphertext diversi.
#   -pbkdf2  : usa PBKDF2 per derivare la chiave dalla passphrase (resistente a brute force
#              molto più del vecchio EVP_BytesToKey).
#   -iter    : numero di iterazioni PBKDF2. 100k è il minimo raccomandato OWASP.
# La passphrase è passata via env per non finire in cleartext nella history shell.
export LAB_PASSPHRASE="correct-horse-battery-staple-42"
openssl enc -aes-256-cbc -salt -pbkdf2 -iter 100000 \
  -in secret.txt \
  -out secret.txt.enc \
  -pass env:LAB_PASSPHRASE
```

```bash
# Verifichiamo che il ciphertext non sia testo leggibile: il primo blocco contiene
# il magic "Salted__" + 8 bytes di salt; il resto è binario indistinguibile da random.
xxd secret.txt.enc | head -3
```

```bash
# Decifriamo con la stessa passphrase. Senza -d il comando re-cifrerebbe; senza -pbkdf2
# fallirebbe perché lo schema di derivazione chiave deve combaciare con la cifratura.
openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 \
  -in secret.txt.enc \
  -out secret.txt.dec \
  -pass env:LAB_PASSPHRASE
```

```bash
# Verifichiamo l'integrità byte-a-byte: il file decifrato deve essere identico all'originale.
diff secret.txt secret.txt.dec && echo "Roundtrip OK"
```

**Expected outcome:**
- `secret.txt.enc` è un file binario che inizia con `Salted__`
- `secret.txt.dec` è byte-identico a `secret.txt`
- Il comando `diff` non produce output e ritorna exit 0

**Success criterion:**

```bash
test -s secret.txt.enc \
  && head -c 8 secret.txt.enc | grep -q "Salted__" \
  && diff -q secret.txt secret.txt.dec > /dev/null
```

---

## Task 3 — Asymmetric Encryption with RSA and Hybrid Scheme

**Goal:** Generare una keypair RSA-2048, cifrare un piccolo payload con la chiave pubblica e decifrarlo con la privata. Implementare poi lo schema ibrido RSA+AES per superare il limite di payload di RSA.

**Steps:**

```bash
# Generiamo una chiave privata RSA da 2048 bit. 2048 è il minimo accettabile oggi
# (NIST raccomanda 3072+ per protezione oltre il 2030, ma 2048 è ancora la baseline
# per certificati TLS e firme). La chiave privata contiene anche tutti i parametri pubblici.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out rsa_private.pem
```

```bash
# Estraiamo la chiave pubblica dalla privata. È questa che condivideremo:
# chiunque la possieda può cifrare per noi, ma solo noi possiamo decifrare.
openssl rsa -in rsa_private.pem -pubout -out rsa_public.pem
```

```bash
# Ispezioniamo le chiavi per vedere modulo e esponente.
openssl rsa -in rsa_private.pem -noout -text | head -5
openssl rsa -in rsa_public.pem -pubin -noout -text | head -3
```

**File to create:** `small_message.txt`

```text
RSA can only encrypt payloads smaller than the key size (minus padding overhead).
For 2048-bit RSA with OAEP padding, the max plaintext is ~190 bytes.
This message is short enough.
```

```bash
# Cifriamo il piccolo messaggio direttamente con RSA. Usiamo OAEP (Optimal Asymmetric
# Encryption Padding): è lo standard moderno, resistente a chosen-ciphertext attack.
# Il vecchio PKCS#1 v1.5 è ancora supportato ma è vulnerabile (attacco di Bleichenbacher).
openssl pkeyutl -encrypt \
  -inkey rsa_public.pem -pubin \
  -pkeyopt rsa_padding_mode:oaep \
  -pkeyopt rsa_oaep_md:sha256 \
  -in small_message.txt \
  -out small_message.enc
```

```bash
# Decifriamo con la chiave privata. Stesso padding mode e digest, altrimenti fallisce.
openssl pkeyutl -decrypt \
  -inkey rsa_private.pem \
  -pkeyopt rsa_padding_mode:oaep \
  -pkeyopt rsa_oaep_md:sha256 \
  -in small_message.enc \
  -out small_message.dec
diff small_message.txt small_message.dec && echo "RSA roundtrip OK"
```

Ora implementiamo lo schema **ibrido** RSA+AES per gestire file di qualsiasi dimensione.
RSA è ~1000× più lento di AES e ha un limite di payload duro. Lo schema reale è:
1. Generare una chiave AES casuale (la "session key" o "data encryption key")
2. Cifrare i dati con AES
3. Cifrare la chiave AES con RSA pubblica del destinatario
4. Inviare insieme i due ciphertext

```bash
# Creiamo un file "grande" (1 MB di dati casuali) che non potrebbe essere cifrato direttamente con RSA.
openssl rand 1048576 -out big_file.bin
ls -lh big_file.bin
```

```bash
# Step 1: generiamo una chiave AES-256 random (32 bytes binari, NON base64).
openssl rand 32 -out aes_session.key
```

```bash
# Step 2: cifriamo big_file.bin con la session key. Passiamo la chiave esadecimale via -K
# e un IV random via -iv. Salviamo l'IV separatamente perché serve in decifratura.
openssl rand -hex 16 > aes_session.iv
openssl enc -aes-256-cbc \
  -K "$(xxd -p -c 64 aes_session.key)" \
  -iv "$(cat aes_session.iv)" \
  -in big_file.bin \
  -out big_file.bin.enc
```

```bash
# Step 3: cifriamo la session key con RSA pubblica. Output piccolo (256 bytes per RSA-2048).
openssl pkeyutl -encrypt \
  -inkey rsa_public.pem -pubin \
  -pkeyopt rsa_padding_mode:oaep \
  -pkeyopt rsa_oaep_md:sha256 \
  -in aes_session.key \
  -out aes_session.key.enc
```

```bash
# A questo punto potremmo cancellare aes_session.key (la chiave in chiaro) e inviare
# soltanto big_file.bin.enc + aes_session.key.enc + aes_session.iv al destinatario.
# Simuliamo questo passaggio:
rm aes_session.key
```

```bash
# Step 4 (lato ricevente): decifriamo la session key con la chiave privata RSA.
openssl pkeyutl -decrypt \
  -inkey rsa_private.pem \
  -pkeyopt rsa_padding_mode:oaep \
  -pkeyopt rsa_oaep_md:sha256 \
  -in aes_session.key.enc \
  -out aes_session.key.recovered
```

```bash
# Step 5: usiamo la session key recuperata per decifrare il file grande.
openssl enc -d -aes-256-cbc \
  -K "$(xxd -p -c 64 aes_session.key.recovered)" \
  -iv "$(cat aes_session.iv)" \
  -in big_file.bin.enc \
  -out big_file.bin.dec
```

```bash
# Confronto finale: il file decifrato deve coincidere con l'originale.
cmp big_file.bin big_file.bin.dec && echo "Hybrid encryption OK"
```

**Expected outcome:**
- `rsa_private.pem` e `rsa_public.pem` esistono e sono parsabili
- `small_message.dec` è identico a `small_message.txt`
- `big_file.bin.dec` è byte-identico a `big_file.bin` (verificato con `cmp`)
- La session key in chiaro è stata cancellata; solo il ciphertext + la versione RSA-cifrata rimangono

**Success criterion:**

```bash
test -s rsa_private.pem \
  && openssl rsa -in rsa_private.pem -noout -check 2>/dev/null \
  && cmp -s small_message.txt small_message.dec \
  && cmp -s big_file.bin big_file.bin.dec \
  && ! test -e aes_session.key
```

---

## Task 4 — AWS CLI Setup, S3 Bucket, IAM User with Least Privilege

**Goal:** Creare un bucket S3 con block public access, un utente IAM con inline policy ristretta a `s3:PutObject` e `s3:GetObject` sul solo bucket, e generare le sue credenziali di accesso programmatico.

**Steps:**

```bash
# Definiamo i nomi delle risorse. Il bucket name deve essere globalmente unico,
# quindi includiamo USER_PREFIX. Il nome utente IAM è unico solo all'interno dell'account.
export BUCKET_NAME="${USER_PREFIX}-crypto-lab"
export IAM_USER_NAME="${USER_PREFIX}-crypto-user"
export IAM_POLICY_NAME="${USER_PREFIX}-crypto-s3-policy"
echo "BUCKET_NAME=$BUCKET_NAME"
echo "IAM_USER_NAME=$IAM_USER_NAME"
```

```bash
# Creiamo il bucket in eu-west-1. Per regioni diverse da us-east-1 dobbiamo specificare
# il LocationConstraint, altrimenti AWS rifiuta la richiesta.
aws s3api create-bucket \
  --bucket "$BUCKET_NAME" \
  --region "$AWS_REGION" \
  --create-bucket-configuration LocationConstraint="$AWS_REGION"
```

```bash
# Abilitiamo il Block Public Access a livello bucket: blocca qualunque ACL o policy
# accidentale che renderebbe pubblici gli oggetti. È la baseline di sicurezza S3.
aws s3api put-public-access-block \
  --bucket "$BUCKET_NAME" \
  --public-access-block-configuration \
    "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true"
```

```bash
# Creiamo l'utente IAM. Non aggiungiamo l'utente a nessun gruppo: assegneremo solo una inline policy.
aws iam create-user --user-name "$IAM_USER_NAME"
```

**File to create:** `s3-least-privilege-policy.json`

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowPutAndGetOnLabBucket",
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:GetObject"
      ],
      "Resource": [
        "arn:aws:s3:::BUCKET_PLACEHOLDER/*"
      ]
    },
    {
      "Sid": "AllowListBucketForVerification",
      "Effect": "Allow",
      "Action": [
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::BUCKET_PLACEHOLDER"
      ]
    }
  ]
}
```

```bash
# Sostituiamo il placeholder con il bucket reale. Lasciamo ListBucket perché AWS CLI
# spesso fa una HEAD/LIST implicita prima di un cp, e senza essa l'errore sarebbe confuso.
# Il principio di least privilege è rispettato: nessun delete, nessun policy management.
sed "s|BUCKET_PLACEHOLDER|$BUCKET_NAME|g" s3-least-privilege-policy.json > s3-policy-final.json
cat s3-policy-final.json
```

```bash
# Attacchiamo la policy come inline policy all'utente. Inline = vive solo per questo utente,
# non è una policy IAM gestita riutilizzabile. Adatto per credenziali per-utente.
aws iam put-user-policy \
  --user-name "$IAM_USER_NAME" \
  --policy-name "$IAM_POLICY_NAME" \
  --policy-document file://s3-policy-final.json
```

```bash
# Generiamo Access Key e Secret Key. ATTENZIONE: il Secret Access Key è mostrato UNA SOLA VOLTA.
# Lo salviamo subito in un file locale (non commettere in git!) per usarlo nel profilo CLI.
aws iam create-access-key --user-name "$IAM_USER_NAME" > iam_credentials.json
export RESTRICTED_ACCESS_KEY_ID=$(jq -r '.AccessKey.AccessKeyId' iam_credentials.json)
export RESTRICTED_SECRET_KEY=$(jq -r '.AccessKey.SecretAccessKey' iam_credentials.json)
echo "Access Key ID created: $RESTRICTED_ACCESS_KEY_ID"
```

```bash
# Configuriamo un profilo CLI separato `crypto-restricted` con queste credenziali.
# Il profilo punta agli stessi region; resta isolato dal profilo admin `lab-provisioned`.
aws configure set aws_access_key_id "$RESTRICTED_ACCESS_KEY_ID" --profile crypto-restricted
aws configure set aws_secret_access_key "$RESTRICTED_SECRET_KEY" --profile crypto-restricted
aws configure set region "$AWS_REGION" --profile crypto-restricted
```

```bash
# La propagazione delle credenziali IAM è eventuale: una nuova access key può non essere
# riconosciuta immediatamente. Aspettiamo ~10 secondi prima di testarla.
echo "Waiting 15s for IAM credential propagation..."
sleep 15
```

```bash
# Test della least privilege: l'utente ristretto deve poter mettere e leggere oggetti sul bucket.
echo "test from restricted user" > probe.txt
aws --profile crypto-restricted s3 cp probe.txt "s3://$BUCKET_NAME/probe.txt"
aws --profile crypto-restricted s3 cp "s3://$BUCKET_NAME/probe.txt" probe_downloaded.txt
diff probe.txt probe_downloaded.txt && echo "Restricted profile PutObject/GetObject OK"
```

```bash
# Verifichiamo che l'utente NON possa fare operazioni non concesse (es. delete).
# Ci aspettiamo un errore AccessDenied: catturiamo lo stderr e verifichiamo che fallisca.
if aws --profile crypto-restricted s3 rm "s3://$BUCKET_NAME/probe.txt" 2>/dev/null; then
  echo "ERROR: delete should not be allowed!"
  exit 1
else
  echo "Confirmed: delete is denied as expected (least privilege working)"
fi
```

**Expected outcome:**
- Bucket `$BUCKET_NAME` esiste in `eu-west-1` con block public access attivo
- Utente IAM `$IAM_USER_NAME` esiste con una sola inline policy
- Profilo CLI `crypto-restricted` può fare PUT e GET sul bucket
- Profilo CLI `crypto-restricted` non può fare DELETE (AccessDenied)

**Success criterion:**

```bash
aws s3api head-bucket --bucket "$BUCKET_NAME" 2>/dev/null \
  && aws iam get-user-policy --user-name "$IAM_USER_NAME" --policy-name "$IAM_POLICY_NAME" > /dev/null \
  && aws --profile crypto-restricted s3api head-object --bucket "$BUCKET_NAME" --key probe.txt > /dev/null \
  && ! aws --profile crypto-restricted s3 rm "s3://$BUCKET_NAME/probe.txt" 2>/dev/null
```

---

## Task 5 — Self-Signed Certificate with SAN on an EC2 nginx Instance

**Goal:** Lanciare un'istanza EC2 t3.micro su Amazon Linux 2023 con nginx, generare un certificato X.509 self-signed con Subject Alternative Name e installarlo per servire HTTPS sulla porta 443.

**Steps:**

```bash
# Definiamo i nomi delle risorse EC2.
export KEY_PAIR_NAME="${USER_PREFIX}-crypto-keypair"
export SG_NAME="${USER_PREFIX}-crypto-sg"
export INSTANCE_NAME="${USER_PREFIX}-crypto-nginx"
```

```bash
# Creiamo una key pair EC2 e salviamo la chiave privata in locale con permessi 0400.
# Senza questi permessi ssh rifiuta di usare la chiave.
aws ec2 create-key-pair \
  --key-name "$KEY_PAIR_NAME" \
  --query 'KeyMaterial' --output text > "${KEY_PAIR_NAME}.pem"
chmod 400 "${KEY_PAIR_NAME}.pem"
```

```bash
# Recuperiamo il VPC default e una subnet pubblica in eu-west-1 per lanciare l'istanza.
export VPC_ID=$(aws ec2 describe-vpcs --filters "Name=isDefault,Values=true" \
  --query 'Vpcs[0].VpcId' --output text)
export SUBNET_ID=$(aws ec2 describe-subnets --filters "Name=vpc-id,Values=$VPC_ID" \
  --query 'Subnets[0].SubnetId' --output text)
echo "VPC_ID=$VPC_ID  SUBNET_ID=$SUBNET_ID"
```

```bash
# Creiamo un security group dedicato. SSH (22) ristretto al nostro IP, HTTPS (443) aperto al mondo
# perché vogliamo verificare con curl da remoto. NON apriamo la 80: il lab è solo HTTPS.
export SG_ID=$(aws ec2 create-security-group \
  --group-name "$SG_NAME" \
  --description "Crypto lab nginx HTTPS" \
  --vpc-id "$VPC_ID" \
  --query 'GroupId' --output text)
aws ec2 authorize-security-group-ingress --group-id "$SG_ID" \
  --protocol tcp --port 22 --cidr "${MY_IP}/32"
aws ec2 authorize-security-group-ingress --group-id "$SG_ID" \
  --protocol tcp --port 443 --cidr 0.0.0.0/0
```

```bash
# Risolviamo dinamicamente l'AMI più recente di Amazon Linux 2023 per eu-west-1.
# SSM Parameter Store espone questo metadato come parametro pubblico standard.
export AMI_ID=$(aws ssm get-parameters \
  --names /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
  --query 'Parameters[0].Value' --output text)
echo "AMI_ID=$AMI_ID"
```

**File to create:** `nginx-userdata.sh`

```bash
#!/bin/bash
# User-data che gira al primo boot dell'istanza. Installa nginx ma NON lo configura
# per HTTPS: faremo il setup TLS dopo, da remoto via SSH, una volta che avremo il certificato.
set -euxo pipefail
dnf update -y
dnf install -y nginx
systemctl enable nginx
# Server temporaneo HTTP-only solo per verificare che il pacchetto funzioni.
# Verrà rimpiazzato in un secondo momento.
systemctl start nginx
```

```bash
# Lanciamo l'istanza. Tag per identificarla nel cleanup. Associate public IP esplicito.
export INSTANCE_ID=$(aws ec2 run-instances \
  --image-id "$AMI_ID" \
  --instance-type t3.micro \
  --key-name "$KEY_PAIR_NAME" \
  --security-group-ids "$SG_ID" \
  --subnet-id "$SUBNET_ID" \
  --associate-public-ip-address \
  --user-data file://nginx-userdata.sh \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$INSTANCE_NAME},{Key=lab,Value=cryptography-openssl}]" \
  --query 'Instances[0].InstanceId' --output text)
echo "INSTANCE_ID=$INSTANCE_ID"
```

```bash
# Aspettiamo che l'istanza passi i 2/2 status checks. Senza questo i passi seguenti
# fallirebbero con connection refused (cloud-init ancora in corso).
echo "Waiting for instance to be running and status OK..."
aws ec2 wait instance-running --instance-ids "$INSTANCE_ID"
aws ec2 wait instance-status-ok --instance-ids "$INSTANCE_ID"
```

```bash
# Recuperiamo il public DNS dell'istanza: lo useremo come CN del certificato e come target di curl.
export EC2_PUBLIC_DNS=$(aws ec2 describe-instances --instance-ids "$INSTANCE_ID" \
  --query 'Reservations[0].Instances[0].PublicDnsName' --output text)
export EC2_PUBLIC_IP=$(aws ec2 describe-instances --instance-ids "$INSTANCE_ID" \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
echo "EC2_PUBLIC_DNS=$EC2_PUBLIC_DNS"
echo "EC2_PUBLIC_IP=$EC2_PUBLIC_IP"
```

**File to create:** `san.cnf`

```ini
[ req ]
distinguished_name = req_distinguished_name
req_extensions     = req_ext
prompt             = no

[ req_distinguished_name ]
C  = IT
O  = Recube Lab
CN = CN_PLACEHOLDER

[ req_ext ]
subjectAltName = @alt_names

[ alt_names ]
DNS.1 = CN_PLACEHOLDER
IP.1  = IP_PLACEHOLDER
```

```bash
# Sostituiamo i placeholder con i valori reali dell'istanza.
sed -e "s|CN_PLACEHOLDER|$EC2_PUBLIC_DNS|g" -e "s|IP_PLACEHOLDER|$EC2_PUBLIC_IP|g" \
  san.cnf > san_final.cnf
cat san_final.cnf
```

```bash
# Generiamo chiave privata + CSR + certificato self-signed in un solo comando.
# -nodes: chiave privata non cifrata (nginx la deve leggere senza passphrase).
# -days 365: validità un anno (sufficiente per il lab).
# -extensions req_ext: include la sezione SAN nel certificato finale.
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout nginx.key \
  -out nginx.crt \
  -days 365 \
  -config san_final.cnf \
  -extensions req_ext
```

```bash
# Verifichiamo che il certificato contenga effettivamente la SAN.
# Senza -text non vedremmo le estensioni X.509v3.
openssl x509 -in nginx.crt -noout -text | grep -A1 "Subject Alternative Name"
```

**File to create:** `nginx-ssl.conf`

```nginx
server {
    listen 443 ssl;
    server_name _;

    ssl_certificate     /etc/nginx/ssl/nginx.crt;
    ssl_certificate_key /etc/nginx/ssl/nginx.key;

    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;

    location / {
        return 200 "Hello from nginx with self-signed cert on $hostname\n";
        add_header Content-Type text/plain;
    }
}
```

```bash
# Trasferiamo cert, chiave e config sull'istanza via SCP. Usiamo StrictHostKeyChecking=no
# perché è la prima connessione e non abbiamo ancora l'host key nel known_hosts.
scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
  -i "${KEY_PAIR_NAME}.pem" \
  nginx.crt nginx.key nginx-ssl.conf \
  "ec2-user@${EC2_PUBLIC_DNS}:/tmp/"
```

```bash
# Eseguiamo sull'istanza i comandi per: creare la dir SSL, spostare i file, installare la nuova
# config nginx e riavviare il servizio. Heredoc remoto = un solo round-trip SSH.
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
  -i "${KEY_PAIR_NAME}.pem" \
  "ec2-user@${EC2_PUBLIC_DNS}" 'bash -s' <<'REMOTE'
set -euxo pipefail
sudo mkdir -p /etc/nginx/ssl
sudo mv /tmp/nginx.crt /etc/nginx/ssl/nginx.crt
sudo mv /tmp/nginx.key /etc/nginx/ssl/nginx.key
sudo chmod 600 /etc/nginx/ssl/nginx.key
sudo mv /tmp/nginx-ssl.conf /etc/nginx/conf.d/ssl.conf
# Rimuoviamo il default server HTTP per evitare conflitto sulla porta 80
# (anche se 80 non è esposta nel SG, evitiamo configurazioni residue).
sudo rm -f /etc/nginx/conf.d/default.conf
sudo nginx -t
sudo systemctl restart nginx
REMOTE
```

```bash
# Diamo a nginx qualche secondo per essere pienamente up sulla porta 443.
sleep 5
```

```bash
# Verifichiamo con curl. -k = ignora errori di trust (il cert è self-signed e non c'è una CA fidata).
# -f = exit code != 0 se HTTP status >= 400. -s = silenzia il progress. -v in caso di debug.
curl -kf "https://${EC2_PUBLIC_DNS}/" -o nginx_response.txt
cat nginx_response.txt
```

```bash
# Verifichiamo anche che il certificato presentato sia quello giusto (CN/SAN match).
echo | openssl s_client -connect "${EC2_PUBLIC_DNS}:443" -servername "${EC2_PUBLIC_DNS}" 2>/dev/null \
  | openssl x509 -noout -subject -ext subjectAltName
```

**Expected outcome:**
- L'istanza EC2 è in stato `running` con 2/2 status check
- `nginx.crt` contiene una sezione X.509v3 `Subject Alternative Name` con il DNS e l'IP dell'istanza
- `https://$EC2_PUBLIC_DNS/` risponde 200 con il corpo "Hello from nginx with self-signed cert"
- Il certificato server presenta CN = public DNS dell'istanza

**Success criterion:**

```bash
aws ec2 describe-instances --instance-ids "$INSTANCE_ID" \
  --query 'Reservations[0].Instances[0].State.Name' --output text | grep -q running \
  && openssl x509 -in nginx.crt -noout -text | grep -q "Subject Alternative Name" \
  && curl -kf -s "https://${EC2_PUBLIC_DNS}/" | grep -q "Hello from nginx"
```

---

## Task 6 — Client-Side Encryption (CSE) of an S3 Object

**Goal:** Cifrare localmente un file con AES-256-CBC, caricarlo su S3 come oggetto opaco, e verificare che chi accede al bucket senza la passphrase vede solo bytes random. Decifrare poi in locale dopo il download.

**Steps:**

**File to create:** `sensitive_data.csv`

```text
employee_id,email,salary_eur
E001,alice@example.com,75000
E002,bob@example.com,82000
E003,charlie@example.com,69000
```

```bash
# Generiamo una passphrase CSE separata da quella usata in Task 2: principio di
# rotazione/separazione delle chiavi per workload diversi.
export CSE_PASSPHRASE="cse-$(openssl rand -base64 18)"
echo "CSE_PASSPHRASE generated (save it now): $CSE_PASSPHRASE"
```

```bash
# Cifriamo il CSV. PBKDF2 + 200k iterazioni (più alte del Task 2 perché il dato è più
# sensibile e ci aspettiamo che possa essere brute-forced offline da chi compromette S3).
openssl enc -aes-256-cbc -salt -pbkdf2 -iter 200000 \
  -in sensitive_data.csv \
  -out sensitive_data.csv.enc \
  -pass env:CSE_PASSPHRASE
```

```bash
# Carichiamo il ciphertext usando il profilo least-privilege creato in Task 4.
# Aggiungiamo un tag per identificare l'oggetto come CSE in metadata.
aws --profile crypto-restricted s3 cp \
  sensitive_data.csv.enc \
  "s3://$BUCKET_NAME/cse/sensitive_data.csv.enc" \
  --metadata "encryption=client-side-aes256-cbc-pbkdf2"
```

```bash
# Verifichiamo che, scaricando l'oggetto crudo, NON sia leggibile come CSV.
# Il primo blocco contiene "Salted__" + salt random; il resto è bianco/binario.
aws --profile crypto-restricted s3 cp \
  "s3://$BUCKET_NAME/cse/sensitive_data.csv.enc" \
  downloaded.enc
file downloaded.enc
head -c 8 downloaded.enc | od -c | head -1
```

```bash
# Decifriamo localmente con la passphrase. Il file in chiaro non lascia mai la nostra workstation.
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 \
  -in downloaded.enc \
  -out sensitive_data.csv.recovered \
  -pass env:CSE_PASSPHRASE
diff sensitive_data.csv sensitive_data.csv.recovered && echo "CSE roundtrip OK"
```

```bash
# Dimostriamo che SENZA la passphrase, anche avendo accesso S3, l'oggetto è inutile.
# Usiamo una passphrase sbagliata: openssl ritorna exit != 0 con "bad decrypt".
if openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 \
    -in downloaded.enc -out /tmp/junk.csv \
    -pass pass:wrong-passphrase 2>/dev/null; then
  echo "ERROR: decryption with wrong passphrase should have failed!"
  exit 1
else
  echo "Confirmed: wrong passphrase yields bad decrypt"
fi
```

**Expected outcome:**
- `sensitive_data.csv.enc` esiste su S3 nel prefix `cse/`
- L'oggetto in S3, scaricato senza decifrare, mostra header `Salted__` e contenuto binario
- `sensitive_data.csv.recovered` è identico all'originale dopo decifratura con la passphrase corretta
- Decifratura con passphrase errata fallisce con exit code != 0

**Success criterion:**

```bash
aws --profile crypto-restricted s3api head-object \
    --bucket "$BUCKET_NAME" --key cse/sensitive_data.csv.enc > /dev/null \
  && diff -q sensitive_data.csv sensitive_data.csv.recovered > /dev/null
```

---

## Task 7 — Server-Side Encryption with KMS (SSE-KMS)

**Goal:** Creare una Customer Managed KMS Key, caricare un oggetto S3 con SSE-KMS, verificare la decifratura trasparente, e osservare l'errore di Access Denied quando la chiave viene disabilitata.

**Steps:**

```bash
# Definiamo i nomi delle risorse KMS. L'alias rende la chiave referenziabile per nome
# anche dopo deletion/recreation (l'ARN cambierebbe, l'alias no).
export KMS_ALIAS="alias/${USER_PREFIX}-crypto-cmk"
```

```bash
# Creiamo una Customer Managed Key (CMK) simmetrica per uso ENCRYPT_DECRYPT.
# Il default key policy concede all'account ROOT le permission di management,
# il che è sufficiente per il lab. In produzione si vorrebbe una policy più stretta.
export KMS_KEY_ARN=$(aws kms create-key \
  --description "Crypto lab CMK for SSE-KMS demo" \
  --key-usage ENCRYPT_DECRYPT \
  --key-spec SYMMETRIC_DEFAULT \
  --tags TagKey=lab,TagValue=cryptography-openssl \
  --query 'KeyMetadata.Arn' --output text)
export KMS_KEY_ID=$(echo "$KMS_KEY_ARN" | awk -F/ '{print $NF}')
echo "KMS_KEY_ARN=$KMS_KEY_ARN"
echo "KMS_KEY_ID=$KMS_KEY_ID"
```

```bash
# Creiamo un alias human-readable. L'alias funge da puntatore stabile alla key.
aws kms create-alias \
  --alias-name "$KMS_ALIAS" \
  --target-key-id "$KMS_KEY_ID"
```

**File to create:** `report.txt`

```text
Q1 financial report — confidential.
Revenue: 1,240,000 EUR.
EBITDA: 312,000 EUR.
```

```bash
# Carichiamo report.txt con SSE-KMS specificando la nostra CMK. NOTA: usiamo il profilo
# ADMIN (`lab-provisioned`) per il primo upload perché il profilo `crypto-restricted` NON
# ha permission `kms:Encrypt` / `kms:GenerateDataKey` sulla nostra CMK. È una scelta
# didattica: in produzione concederemmo via key policy il diritto di uso al ruolo applicativo.
aws s3 cp report.txt "s3://$BUCKET_NAME/sse-kms/report.txt" \
  --sse aws:kms \
  --sse-kms-key-id "$KMS_KEY_ARN"
```

```bash
# Inspectionamo i metadata dell'oggetto: ServerSideEncryption deve essere "aws:kms"
# e SSEKMSKeyId deve combaciare con il nostro ARN.
aws s3api head-object \
  --bucket "$BUCKET_NAME" \
  --key sse-kms/report.txt \
  > sse_metadata.json
cat sse_metadata.json | jq '{ServerSideEncryption, SSEKMSKeyId}'
```

```bash
# Scarichiamo l'oggetto: S3 + KMS si occupano della decifratura in modo trasparente.
# Il client riceve il plaintext senza fornire chiavi (a patto di avere kms:Decrypt).
aws s3 cp "s3://$BUCKET_NAME/sse-kms/report.txt" report_downloaded.txt
diff report.txt report_downloaded.txt && echo "SSE-KMS transparent decrypt OK"
```

```bash
# Disabilitiamo la KMS key per simulare la revoca dell'accesso (es. data breach detection).
# Una chiave disabilitata non può essere usata per encrypt né decrypt, ma esiste ancora.
aws kms disable-key --key-id "$KMS_KEY_ID"
echo "KMS key disabled. Waiting 10s for the change to propagate..."
sleep 10
```

```bash
# Tentiamo di scaricare di nuovo l'oggetto: ci aspettiamo un errore.
# Catturiamo lo stderr e verifichiamo che il comando fallisca.
if aws s3 cp "s3://$BUCKET_NAME/sse-kms/report.txt" /tmp/should_fail.txt 2>fail_reason.txt; then
  echo "ERROR: download should have failed with key disabled!"
  exit 1
else
  echo "Confirmed: GetObject failed because KMS key is disabled."
  cat fail_reason.txt
fi
```

```bash
# Ri-abilitiamo la chiave per poter procedere con il cleanup ordinato del lab.
aws kms enable-key --key-id "$KMS_KEY_ID"
echo "KMS key re-enabled."
sleep 5
```

```bash
# Verifica finale: ora il download funziona di nuovo.
aws s3 cp "s3://$BUCKET_NAME/sse-kms/report.txt" report_downloaded2.txt
diff report.txt report_downloaded2.txt && echo "SSE-KMS re-enabled OK"
```

**Expected outcome:**
- La CMK esiste con alias `alias/<USER_PREFIX>-crypto-cmk`
- `s3://$BUCKET_NAME/sse-kms/report.txt` ha `ServerSideEncryption: aws:kms` nei metadata
- Il download è trasparente quando la chiave è enabled
- Il download fallisce con AccessDenied (o KMS.DisabledException) quando la chiave è disabled
- Dopo `enable-key` il download torna a funzionare

**Success criterion:**

```bash
aws s3api head-object --bucket "$BUCKET_NAME" --key sse-kms/report.txt \
    --query 'ServerSideEncryption' --output text | grep -q "aws:kms" \
  && aws s3api head-object --bucket "$BUCKET_NAME" --key sse-kms/report.txt \
    --query 'SSEKMSKeyId' --output text | grep -q "$KMS_KEY_ID" \
  && diff -q report.txt report_downloaded2.txt > /dev/null
```

---

## Cleanup

Esegui i comandi seguenti nell'ordine indicato per rimuovere tutte le risorse create. L'ordine è importante: dipendenze interne (es. access keys prima dell'utente) impediscono delete prematuri.

```bash
# 1. Terminiamo l'istanza EC2 e attendiamo che sia effettivamente terminated
# prima di toccare il security group (un SG con ENI attivi non è deletable).
aws ec2 terminate-instances --instance-ids "$INSTANCE_ID"
aws ec2 wait instance-terminated --instance-ids "$INSTANCE_ID"
```

```bash
# 2. Rimuoviamo security group e key pair EC2.
aws ec2 delete-security-group --group-id "$SG_ID"
aws ec2 delete-key-pair --key-name "$KEY_PAIR_NAME"
```

```bash
# 3. Svuotiamo il bucket S3 e lo rimuoviamo. `rb --force` cancella anche gli oggetti.
aws s3 rb "s3://$BUCKET_NAME" --force
```

```bash
# 4. Rimuoviamo l'utente IAM: prima le access keys, poi la inline policy, poi l'utente.
for AK in $(aws iam list-access-keys --user-name "$IAM_USER_NAME" \
    --query 'AccessKeyMetadata[].AccessKeyId' --output text); do
  aws iam delete-access-key --user-name "$IAM_USER_NAME" --access-key-id "$AK"
done
aws iam delete-user-policy --user-name "$IAM_USER_NAME" --policy-name "$IAM_POLICY_NAME"
aws iam delete-user --user-name "$IAM_USER_NAME"
```

```bash
# 5. Schedule deletion della KMS key. AWS impone una finestra di 7-30 giorni; 7 è il minimo.
# La chiave NON è eliminata immediatamente: è in stato PendingDeletion e può essere ripristinata.
aws kms delete-alias --alias-name "$KMS_ALIAS"
aws kms schedule-key-deletion --key-id "$KMS_KEY_ID" --pending-window-in-days 7
```

```bash
# 6. Rimuoviamo il profilo CLI ristretto dal config locale.
aws configure set aws_access_key_id "" --profile crypto-restricted
aws configure set aws_secret_access_key "" --profile crypto-restricted
```

```bash
# 7. Rimuoviamo la directory di lavoro locale con tutte le chiavi, certificati e ciphertext.
cd "$HOME"
rm -rf "$LAB_DIR"
```

```bash
# 8. Unset delle variabili d'ambiente per evitare leak in sessioni successive.
unset LAB_PASSPHRASE CSE_PASSPHRASE RESTRICTED_ACCESS_KEY_ID RESTRICTED_SECRET_KEY \
      BUCKET_NAME IAM_USER_NAME IAM_POLICY_NAME KEY_PAIR_NAME SG_NAME INSTANCE_NAME \
      VPC_ID SUBNET_ID SG_ID AMI_ID INSTANCE_ID EC2_PUBLIC_DNS EC2_PUBLIC_IP \
      KMS_ALIAS KMS_KEY_ARN KMS_KEY_ID USER_PREFIX
echo "Cleanup complete."
```

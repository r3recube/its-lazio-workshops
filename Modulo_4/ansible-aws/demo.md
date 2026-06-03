---
lab_id: ansible-aws
generated_at: 2026-06-02T20:50:27.058889+00:00
target_audience: instructor
estimated_classroom_duration_minutes: 90
based_on:
  - lab.md (version 3, draft)
  - concepts.json
  - evaluation.md
---

# Demo Guide — Deploy a Spring Boot Application on AWS EC2 with Ansible

## Come usare questa guida

> Questa guida è per te, non per l'aula. Trovi qui i "perché" dietro ogni task,
> i tempi morti da riempire mentre Maven builda, gli output attesi e i gotcha
> tipici. I comandi sono identici al `lab.md` che hanno i partecipanti — qui
> dentro c'è solo la regia. Tienila aperta in un secondo monitor e usa il
> `lab.md` come copione condiviso.

## Pre-aula checklist

- [ ] AWS account dedicato al workshop con un budget cap impostato (anche solo `t3.micro` lasciate accese consumano).
- [ ] Key Pair `ansible-lab-key` già creato in `eu-west-1` e file `.pem` distribuito ai partecipanti via canale sicuro, con `chmod 600` già applicato.
- [ ] `aws sts get-caller-identity` verificato per ogni set di credenziali distribuito (la più frequente sorgente di "non parte nulla" in aula).
- [ ] Collection `amazon.aws:11.3.0` pre-scaricata localmente (`ansible-galaxy collection install "amazon.aws:>=7.0.0,<12.0.0"`): in valutazione abbiamo visto un HTTP 502 transiente da Galaxy, e se succede a metà aula perdi 5 minuti di silenzio.
- [ ] Su macchine con Ubuntu 24.04+/Python 3.11+ ricordare ai partecipanti `--break-system-packages` o l'uso di `pipx` per `pip install --user`: il lab v3 non documenta il workaround PEP 668 (gotcha confermato in evaluation).
- [ ] Default VPC presente nelle region target di ogni partecipante (`aws ec2 describe-vpcs --filters Name=isDefault,Values=true`): se è stato eliminato, l'EC2 fallisce con `VPCIdNotSpecified` e il troubleshooting è lento.
- [ ] Una EC2 di prova lanciata e terminata 24 ore prima del workshop, per validare che la coda di AMI Amazon Linux 2023 risponda nella region scelta e non ci siano `InsufficientInstanceCapacity` su `t3.micro`.
- [ ] Slide o whiteboard pronti per mostrare l'architettura "due play" (provisioning + configurazione) prima di entrare nel codice del Task 4.

## Arco narrativo del lab

Il filo conduttore è "da zero a deploy in un singolo comando". Partiamo
strutturando il progetto come ce lo aspetteremmo in un repo aziendale (Task 1),
risolviamo il problema dell'IP dinamico delle istanze cloud con l'inventory
dinamico (Task 2), poi costruiamo l'infrastruttura applicando il principio di
minimo privilegio (Task 3) e provisioniamo l'EC2 con AMI risolta a runtime
(Task 4). Il cuore didattico è il role idempotente del Task 5, dove la
domanda guida diventa "cosa succede se rieseguo il playbook?". Il Task 6
chiude il cerchio: verifichiamo l'app con `curl` e dimostriamo
empiricamente l'idempotenza con un secondo run a `changed=0`. La morale è
che un playbook ben scritto non è uno script di deploy: è un meccanismo di
convergenza che possiamo eseguire in sicurezza quante volte vogliamo.

## Task 1 — Scaffolding del progetto e configurazione Ansible

**Durata stimata:** 15 min
**Punto pedagogico chiave:** *un progetto Ansible ben strutturato è il primo
atto di disciplina che paga dividendi per tutto il resto del lab.*

### Narrative framing (cosa dire prima di iniziare)
> "Prima di scrivere qualunque playbook decidiamo dove vivono le cose.
> Ansible carica `ansible.cfg` dalla current directory, quindi quella diventa
> la root del progetto. Separare `inventory/`, `playbooks/`, `roles/` non è
> estetica: è la convenzione che Ansible Galaxy e i tool di CI/CD danno per
> scontata. Faremo anche un fix difensivo che vi sembrerà strano —
> `deprecation_warnings = False` — ma vi spiegherò il motivo dopo, è una
> cicatrice di esperienza."

### Esecuzione

**Step 1.1 — Crea la struttura di directory**

```bash
cd "$LAB_DIR"
mkdir -p inventory playbooks roles/springboot/tasks roles/springboot/defaults roles/springboot/handlers
```

**Mentre il comando gira (~istantaneo):** niente da dire.

**Expected output:**
````
(nessun output — exit 0)
````

**Step 1.2 — Scrivi `ansible.cfg`**

Crea il file `ansible.cfg` nella root del progetto con il contenuto del lab.md.
Mentre i partecipanti scrivono, motiva i tre punti che probabilmente
chiederanno: `roles_path`, `result_format = yaml` (al posto del callback
rimosso), e `deprecation_warnings = False`.

**Mentre i partecipanti incollano (~60s):** "Tre dettagli da sottolineare.
Primo: `roles_path = ./roles` serve perché i playbook stanno in `playbooks/`
e Ansible cercherebbe i role in `playbooks/roles/`. Secondo: `result_format =
yaml` lo usiamo al posto del vecchio callback `community.general.yaml`,
rimosso nella 12.0. Terzo: `deprecation_warnings = False` non è pigrizia —
silenzia un warning interno al plugin `aws_ec2` che non dipende dalla nostra
configurazione e che altrimenti vi farebbe perdere tempo a inseguire un
fantasma."

**Step 1.3 — Installa la collection `amazon.aws`**

```bash
ansible-galaxy collection install "amazon.aws:>=7.0.0,<12.0.0"
```

**Mentre il comando gira (~30-90s):** racconta perché le virgolette sono
obbligatorie ("la shell interpreterebbe `>=` come redirect e vi ritrovereste
un file `=7.0.0` nella working dir"). Approfondisci perché installiamo solo
`amazon.aws` e non `community.general`: il lab non usa nessun modulo della
seconda, e meno collection significa meno superficie di compatibilità.

**Expected output:**
````
Starting galaxy collection install process
Downloading ...amazon-aws-11.3.0.tar.gz
Installing 'amazon.aws:11.3.0' to '/root/.ansible/collections/ansible_collections/amazon/aws'
amazon.aws:11.3.0 was installed successfully
````

**Step 1.4 — Verifica i success criterion**

Esegui il blocco di verifica del lab.md.

**Expected output:**
````
ansible.cfg: OK
roles/springboot/tasks: OK
inventory: OK
playbooks: OK
no spurious '=' files: OK
amazon.aws collection: OK
Exit: 0
````

### Punti di pausa Q&A consigliati

Pausa naturale di 2 minuti. Prompt suggerito: "Domande sulla struttura?
Qualcuno ha già un progetto Ansible esistente che vorrebbe migrare a questa
gerarchia?". Tipicamente arrivano domande su `requirements.yml` vs galaxy
install diretto — risposta breve: il lab semplifica, in produzione `requirements.yml`
versionato in repo è la prassi.

### Gotcha tipici

- **HTTP 502 da Galaxy** (visto in evaluation): è transiente. Il fix è
  rilanciare, oppure passare `--timeout 60`. Se in aula succede, sdrammatizza
  ("è il momento in cui Galaxy fa il suo daily downtime") e rilancia.
- **File `=7.0.0` nella working dir**: virgolette dimenticate. `rm "$LAB_DIR"/=*`
  e rilancia con il comando quotato.
- **Su Ubuntu 24.04+/Python 3.11+**: `pip install --user` fallisce con
  `externally-managed-environment` (PEP 668). Il lab v3 non lo cita: avvisa
  l'aula prima di iniziare e fai usare `pipx install` o
  `--break-system-packages`.

## Task 2 — Inventory dinamico tramite plugin `aws_ec2`

**Durata stimata:** 10 min
**Punto pedagogico chiave:** *l'inventory dinamico è il pattern che rende
Ansible utilizzabile in cloud — qui non sappiamo gli IP, e va bene così.*

### Narrative framing (cosa dire prima di iniziare)
> "In on-prem avete IP statici, mettete una lista in `hosts` e via. In cloud
> gli IP cambiano ad ogni `terminate/launch`. La soluzione non è fare uno
> script che genera il file `hosts`: è dichiarare ad Ansible 'fammi vedere
> tutto quello che ha questo tag, ovunque tu lo trovi'. Questo file lo
> useremo poi in Task 6 per dimostrare che il playbook funziona anche se
> l'IP dell'istanza è cambiato dalla volta scorsa."

### Esecuzione

**Step 2.1 — Crea `inventory/aws_ec2.yml`**

Copia il contenuto dal lab.md. Sottolinea due punti tecnici:

**Mentre i partecipanti scrivono (~90s):** "Due cose importanti. Il file
**deve** finire con `aws_ec2.yml` — è il pattern che il plugin riconosce.
E noi usiamo `ec2_tags.Name`, non `tags.Name`: la variabile `tags` è
deprecata dalla amazon.aws 8 in poi, ed è proprio il warning che abbiamo
silenziato in Task 1."

**Step 2.2 — Verifica che il plugin parsi correttamente**

```bash
cd "$LAB_DIR"
ansible-inventory --graph
```

**Mentre il comando gira (~3s):** "A questo stadio non c'è ancora nessuna
EC2 taggata, quindi vi aspettate `@all:` con `@ungrouped:` sotto, e basta.
Se vedete `Failed to parse` è quasi sempre AWS che dice di no, non il
nostro YAML."

**Expected output:**
````
@all:
  |--@ungrouped:
````

### Punti di pausa Q&A consigliati

Salta — fluisce direttamente nel Task 3.

### Gotcha tipici

- **`Failed to parse ... AuthFailure`** (visto in evaluation): credenziali
  AWS scadute, sbagliate o non esportate nella shell corrente. Fai eseguire
  `aws sts get-caller-identity`. Se fallisce, ri-esporta `AWS_ACCESS_KEY_ID`
  e `AWS_SECRET_ACCESS_KEY` nello stesso terminale.
- **`DEPRECATION WARNING: The 'tags' host variable is deprecated`**: significa
  che `deprecation_warnings = False` non è stato applicato. Controlla che
  sia sotto la sezione `[defaults]` di `ansible.cfg` e che `ansible-inventory`
  sia lanciato da `$LAB_DIR`.
- **Filename sbagliato**: se chiami il file `ec2.yml` invece di `aws_ec2.yml`
  il plugin non lo riconosce e il graph mostra solo `@all:` senza errore —
  silenzioso e molto frustrante.

## Task 3 — Provisioning del Security Group

**Durata stimata:** 10 min
**Punto pedagogico chiave:** *least privilege è un'abitudine, non
un'eccezione — apri solo il tuo /32, mai 0.0.0.0/0, neanche "tanto è un lab".*

### Narrative framing (cosa dire prima di iniziare)
> "Il Security Group è la nostra prima risorsa AWS reale. Approfittiamo per
> stabilire un'abitudine: niente `0.0.0.0/0` sulle porte di management,
> neanche in lab. Esponiamo SSH e 8080 solo dal `/32` del control node — il
> nostro IP pubblico corrente. Se siete dietro NAT aziendale o VPN, l'IP che
> AWS vede è quello dell'uscita NAT, non quello della vostra macchina, ed è
> esattamente quello che vogliamo nel SG."

### Esecuzione

**Step 3.1 — Scrivi `playbooks/security_group.yml`**

Copia dal lab.md. Mentre i partecipanti scrivono, sottolinea che `proto: -1`
in egress significa "tutti i protocolli" — è la sintassi storica di EC2
per "all traffic".

**Mentre i partecipanti scrivono (~2 min):** "Notate il pattern: il playbook
ha `hosts: localhost`, non l'EC2. Tutte le interazioni con AWS sono `delegate`
implicite al control node, che parla con AWS API via boto3. Non c'è ancora
nessuna macchina target."

**Step 3.2 — Esegui il blocco di setup IP + provisioning + cattura SG ID**

```bash
cd "$LAB_DIR"
export MY_IP="$(curl -fsS https://checkip.amazonaws.com)/32"
echo "Your public IP: $MY_IP"
ansible-playbook playbooks/security_group.yml
export LAB_SG_ID=$(aws ec2 describe-security-groups \
  --filters "Name=tag:workshop,Values=$LAB_TAG" \
  --query 'SecurityGroups[0].GroupId' \
  --output text)
echo "Security Group ID: $LAB_SG_ID"
```

**Mentre il comando gira (~15-25s):** "Tre cose in sequenza. Una: prendiamo
il nostro IP pubblico da un servizio AWS — checkip.amazonaws.com è AWS-owned,
non un servizio random. Due: eseguiamo il playbook, che è idempotente — se
lo rilanciate non duplica regole. Tre: esportiamo `LAB_SG_ID` perché ci
servirà nel Task 4 per attaccarlo all'EC2. Tenete tutto nella **stessa
shell**, altrimenti `MY_IP` evapora."

**Expected output:**
````
Your public IP: 203.0.113.42/32

PLAY [Provision lab security group] ********************************************

TASK [Ensure security group exists with required ingress rules] ****************
changed: [localhost]

TASK [Print security group id] *************************************************
ok: [localhost] =>
  msg: 'Security Group ID: sg-0a1b2c3d4e5f6a7b8'

PLAY RECAP *********************************************************************
localhost                  : ok=2    changed=1    unreachable=0    failed=0

Security Group ID: sg-0a1b2c3d4e5f6a7b8
````

### Punti di pausa Q&A consigliati

Pausa di 2-3 minuti. Prompt: "Avete altre porte che secondo voi andrebbero
ristrette in modo simile in un setup reale?". Risposte tipiche: RDP, porte
DB. Buon momento per parlare di **bastion host** come pattern complementare.

### Gotcha tipici

- **`MY_IP` vuota**: hai aperto un nuovo terminale tra Setup e questo step.
  Riesporta `MY_IP`, `LAB_TAG` e le credenziali nello stesso terminale.
- **`InvalidGroup.Duplicate`**: hai già un SG con quel nome in un'altra VPC.
  Il modulo gestisce l'upsert, ma in casi limite (SG con stesso nome in VPC
  diversa) può confondersi. Cancella manualmente con `aws ec2 delete-security-group`
  e rilancia.
- **In valutazione il task è stato SKIPPED** per assenza di credenziali AWS,
  quindi l'output reale del playbook qui non l'abbiamo. Quello che vedi sopra
  è ricostruito sulla base della struttura del modulo `ec2_security_group` —
  realistico ma non catturato in una run live.

## Task 4 — Playbook di provisioning EC2

**Durata stimata:** 15 min
**Punto pedagogico chiave:** *un playbook può creare l'host e poi
configurarlo nello stesso run — `add_host` è il ponte che lo permette.*

### Narrative framing (cosa dire prima di iniziare)
> "Questo è il task in cui Ansible smette di essere 'configuration management'
> e diventa orchestration end-to-end. Abbiamo due play nello stesso playbook:
> la prima crea l'EC2 su localhost, la seconda configura l'EC2 via SSH. Il
> trucco è `add_host`, che registra in memoria l'host appena creato e lo
> rende disponibile alla play successiva. Niente file intermedi, niente
> doppia invocazione. È un pattern che vi farà rimpiangere ogni script bash
> di provisioning che avete scritto."

### Esecuzione

**Step 4.1 — Crea lo stub del role**

```bash
cd "$LAB_DIR"
cat > roles/springboot/tasks/main.yml <<'EOF'
---
# Stub temporaneo. Verrà sovrascritto nel Task 5.
- name: Placeholder
  ansible.builtin.debug:
    msg: stub
EOF
```

**Mentre il comando gira (~istantaneo):** "Lo stub serve solo perché il
syntax-check del prossimo passo vuole risolvere il role `springboot`. Senza
questo file fallisce con 'role not found' anche se la sintassi del playbook
è perfetta."

**Step 4.2 — Scrivi `playbooks/deploy.yml`**

Copia dal lab.md. Mentre i partecipanti scrivono, racconta i punti
significativi.

**Mentre i partecipanti scrivono (~3 min):** "Quattro cose da notare. Uno:
risolviamo l'AMI ID a runtime via SSM Parameter Store, AWS lo mantiene
aggiornato per noi. Due: `wait_for` sulla porta 22 ci protegge dal race
condition tra `running` e SSH effettivamente pronto. Tre: `add_host` aggiunge
l'IP a un gruppo in-memory `app_servers` e setta i parametri SSH per quella
sessione. Quattro: la seconda play parte con `hosts: app_servers` e applica
il role — l'inventory dinamico del Task 2 non viene usato per questa run
perché abbiamo già l'IP fresco in memoria."

**Step 4.3 — Verifica la sintassi**

```bash
cd "$LAB_DIR"
ansible-playbook playbooks/deploy.yml --syntax-check
```

**Mentre il comando gira (~3-4s):** "Piccola onestà intellettuale: il lab
dice che `--syntax-check` non chiama AWS. **Non è proprio vero**: il plugin
inventory `aws_ec2` tenta comunque `ec2:DescribeInstances` (l'abbiamo visto
in evaluation). Se vedete warning sulle credenziali sono attesi, il check
ritorna exit 0 comunque. Quello che `--syntax-check` valida davvero è la
struttura YAML e la risolubilità dei role."

**Expected output:**
````
playbook: playbooks/deploy.yml
````

### Punti di pausa Q&A consigliati

Pausa di 3 minuti. Prompt: "Avreste altri modi per passare l'IP dalla play
1 alla play 2?". Risposte: file temporaneo, `meta: refresh_inventory`,
inventory plugin con cache. Confronta i trade-off in 30 secondi: `add_host`
vince per semplicità ma vive solo nel run corrente.

### Gotcha tipici

- **`The role 'springboot' was not found`**: lo stub non è stato creato o
  `roles_path = ./roles` non è in `ansible.cfg`, o si esegue da una directory
  diversa da `$LAB_DIR`. `pwd` + `grep roles_path ansible.cfg`.
- **`Could not find imported module support code for amazon.aws.ec2_instance`**:
  `boto3`/`botocore` non sono nell'interprete Python usato da Ansible.
  `ansible --version | grep 'python version'` per scoprire quale interprete
  usa, poi installa `boto3>=1.34` e `botocore>=1.34` in quell'ambiente.
- **WARNING di documentazione (da evaluation)**: il syntax-check **fa**
  chiamate AWS contrariamente a quanto afferma il lab. Se i partecipanti
  notano il warning AuthFailure, anticipa la spiegazione invece di ignorarla.

## Task 5 — Role `springboot`: installa toolchain, clona, builda, avvia

**Durata stimata:** 20 min
**Punto pedagogico chiave:** *l'idempotenza non è gratis: è un'opzione di
design che pagate con codice esplicito tipo `changed_when: git_clone.changed`.*

### Narrative framing (cosa dire prima di iniziare)
> "Questo è il pezzo grosso. Scriviamo un role che fa cinque cose: installa
> Java/Maven/Git, crea un utente di sistema, clona, builda, installa una
> unit systemd, avvia il servizio. Mentre lo costruiamo, fatevi una domanda
> per ogni task: 'se rilancio il playbook fra 10 minuti senza cambiare nulla,
> questo task risulterà *changed* o *ok*?'. Quella domanda è la differenza
> tra uno script di deploy e un meccanismo di convergenza."

### Esecuzione

**Step 5.1 — `roles/springboot/defaults/main.yml`**

Copia dal lab.md.

**Mentre i partecipanti scrivono (~60s):** "I `defaults/` sono la
documentazione del role. Chi userà il vostro role guarda qui per capire
cosa è configurabile. Avere `springboot_repo_url` esposto come default
significa che domani questo role può deployare un'altra app Spring senza
toccare il codice."

**Step 5.2 — `roles/springboot/tasks/main.yml`**

Sovrascrive lo stub. Copia dal lab.md. È il pezzo più lungo da scrivere.

**Mentre i partecipanti scrivono (~5-6 min):** dividi il commento in
quattro momenti.

1. (~minuto 1) "Pacchetti e utente — pattern standard, niente di esotico.
   `system: true` per l'utente perché non vogliamo che la GUI lo mostri tra
   gli utenti di login."

2. (~minuto 2) "Il task `Build application JAR` è il cuore didattico del
   role. Notate `changed_when: git_clone.changed`. Senza questa riga ogni
   run del playbook segnalerebbe la build come *changed* e voi pensereste
   'ho rotto qualcosa', mentre la build sarebbe semplicemente la stessa di
   prima. **Disclosure tecnica**: questo controlla solo il *reporting* di
   Ansible. Il comando Maven viene eseguito comunque, ogni volta. Lo
   discuteremo dopo."

3. (~minuto 3) "L'handler `reload systemd` viene notificato solo se la copia
   del file `.service` è effettivamente cambiata. Se la unit non cambia,
   `daemon_reload` non parte. Questo è il pattern idempotente di systemd
   sotto Ansible."

4. (~minuto 4) "`meta: flush_handlers` esegue gli handler pendenti **qui**,
   non a fine play. Lo facciamo perché il task successivo è `Enable and
   start service`: vogliamo che `daemon-reload` avvenga *prima* che systemd
   provi a leggere la unit, non dopo."

**Step 5.3 — `roles/springboot/handlers/main.yml`**

Copia dal lab.md. Tre righe, niente da spiegare oltre alla narrativa già
fatta sopra.

**Step 5.4 — Verifica la sintassi finale**

```bash
cd "$LAB_DIR"
ansible-playbook playbooks/deploy.yml --syntax-check
```

**Expected output:**
````
playbook: playbooks/deploy.yml
````

### Punti di pausa Q&A consigliati

Pausa di 5 minuti. Domanda guida: "Secondo voi, è davvero idempotente? Cosa
succede al *secondo* run del playbook quando il codice non è cambiato?".
Lascia che qualcuno arrivi alla conclusione che il `changed_when` controlla
solo il reporting, non l'esecuzione — Maven gira comunque per 3-5 minuti.
Questo è il momento per introdurre `when: git_clone.changed` come miglioria
opzionale (cita la sezione [IMPROVE] di evaluation: il fix esiste, va
combinato con un check `stat` sul JAR esistente).

### Gotcha tipici

- **`Build application JAR` segnato come `changed` al secondo run**: il
  clone Git ha effettivamente aggiornato i sorgenti (branch remoto avanzato,
  o `force: true` ha sovrascritto modifiche locali). `git -C /opt/springboot-app
  log -1` sull'EC2 per verificare.
- **OOM durante Maven**: `t3.micro` ha 1GB di RAM. `MAVEN_OPTS: -Xmx512m`
  che vedete nel task previene questo. Se i partecipanti modificano il
  comando rimuovendolo, Maven killata da OOM è quasi certa.
- **Idempotenza solo nel reporting (da evaluation, WARNING)**: Maven viene
  rieseguito ad ogni run (3-5 min) anche se nulla è cambiato. Il lab lo
  documenta esplicitamente come scelta di design; se l'aula chiede "come si
  fa per davvero", la risposta è `when: git_clone.changed` + `stat` su JAR
  esistente.

## Task 6 — Esecuzione end-to-end e verifica HTTP

**Durata stimata:** 20 min
**Punto pedagogico chiave:** *l'idempotenza non è un'astrazione: è una
proprietà che potete misurare con un secondo `ansible-playbook` e un
`changed=0`.*

### Narrative framing (cosa dire prima di iniziare)
> "Ora vediamo se ha funzionato. Il primo run dura 4-7 minuti — la parte
> lenta è la build Maven di petclinic, 3-5 minuti su `t3.micro`. Useremo
> quel tempo per parlare. Il secondo run sarà lo stress test: vogliamo
> vedere `changed=0` sulla play di deploy. Se lo otteniamo, abbiamo
> dimostrato empiricamente che il playbook è convergente."

### Esecuzione

**Step 6.1 — Primo run end-to-end**

```bash
cd "$LAB_DIR"
ansible-playbook playbooks/deploy.yml -v
```

**Mentre il comando gira (~4-7 min):** questo è il blocco di tempo più
lungo del lab. Pianifica almeno tre momenti di talking points:

1. (~minuto 1, mentre crea l'EC2) "Stiamo guardando boto3 parlare con
   EC2 API. La risoluzione AMI via SSM è già successa, l'ID è in un
   `set_fact`. Adesso `wait: true` blocca finché lo stato è `running`."

2. (~minuti 2-5, mentre Maven builda) momento perfetto per la storia di
   Maven petclinic — è la demo app ufficiale Spring, fat JAR, ~50MB.
   Approfondisci il concetto di `BUILD SUCCESS` e perché *non* l'abbiamo
   usato come `changed_when` (sarebbe stato sempre vero).

3. (~minuto 6, mentre systemd avvia) "`wait_for` sulla porta 8080 da
   localhost dell'EC2 è il nostro health check di sistema. La JVM ha bisogno
   di tempo per il warm-up Spring; questo task evita che il playbook dichiari
   vittoria troppo presto."

**Expected output (finale, PLAY RECAP):**
````
PLAY RECAP *********************************************************************
localhost                  : ok=7    changed=3    unreachable=0    failed=0
54.195.10.23               : ok=12   changed=10   unreachable=0    failed=0
````

**Step 6.2 — Recupera l'IP e verifica con `curl`**

```bash
export INSTANCE_PUBLIC_IP=$(aws ec2 describe-instances \
  --filters "Name=tag:workshop,Values=$LAB_TAG" "Name=instance-state-name,Values=running" \
  --query 'Reservations[0].Instances[0].PublicIpAddress' \
  --output text)
echo "Instance public IP: $INSTANCE_PUBLIC_IP"

for attempt in 1 2 3 4 5 6; do
  if curl -fsS --max-time 10 "http://${INSTANCE_PUBLIC_IP}:8080/" -o /tmp/lab_response.html; then
    echo "App responded on attempt $attempt"
    break
  fi
  echo "Attempt $attempt failed, retrying in 20s..."
  sleep 20
done
head -c 500 /tmp/lab_response.html
```

**Mentre il comando gira (~10-30s):** "Usiamo `aws ec2 describe-instances`
e non l'inventory dinamico perché vogliamo un valore deterministico —
nessuna cache di mezzo. Il retry su `curl` è perché la JVM ha tempi di
warm-up variabili tra istanze."

**Expected output:**
````
Instance public IP: 54.195.10.23
App responded on attempt 1
<!DOCTYPE html>
<html lang="en">
<head>
<title>PetClinic :: a Spring Framework demonstration</title>
````

**Step 6.3 — Secondo run per verificare l'idempotenza**

```bash
cd "$LAB_DIR"
ansible-playbook playbooks/deploy.yml | tee /tmp/lab_second_run.txt
```

**Mentre il comando gira (~3-5 min):** "Qui dovremmo vedere praticamente
tutti i task `ok` invece di `changed`. L'eccezione, lo sapete, è
`Build application JAR`: viene rieseguito perché abbiamo solo controllato il
*reporting*. Ma sul PLAY RECAP della seconda play vogliamo `changed=0`.
Questa è la dimostrazione empirica dell'idempotenza."

**Expected output (PLAY RECAP secondo run):**
````
PLAY RECAP *********************************************************************
localhost                  : ok=7    changed=0    unreachable=0    failed=0
54.195.10.23               : ok=12   changed=0    unreachable=0    failed=0
````

### Punti di pausa Q&A consigliati

Pausa di 5 minuti — questo è il momento finale di Q&A approfondito prima
del wrap-up. Prompt: "Cosa cambieresti per portare questo playbook in
produzione?". Risposte attese: secret in Vault o env, role `requirements.yml`
versionato, CI che esegue `--check`, tag/blocchi per esecuzione parziale,
Maven build in CI separata.

### Gotcha tipici

- **BLOCKING (da evaluation, non risolto in v3)**: il success criterion
  ufficiale del lab usa `grep -E "app_servers.*changed=0"`. Quel pattern
  **non matcha mai** perché PLAY RECAP mostra l'IP dell'host
  (`54.195.10.23`), non il nome del gruppo. Avvisa l'aula: se il check
  fallisce ma il PLAY RECAP mostra `changed=0`, hanno completato il task —
  il pattern del lab è bacato. Suggerisci di sostituire con
  `grep -E "${INSTANCE_PUBLIC_IP}.*changed=0"` oppure
  `! grep -E "changed=[1-9][0-9]*" /tmp/lab_second_run.txt`.
- **`wait_for` su porta 8080 va in timeout**: la build è andata male o la
  JVM non parte. Sull'EC2: `sudo journalctl -u springboot-app -n 200`,
  `ls /opt/springboot-app/target/`, `sudo ss -tlnp | grep 8080`.
- **`curl` ritorna `Connection refused` ma il playbook è verde**: tra
  `wait_for` interno (localhost EC2) e `curl` esterno c'è il Security
  Group. Verifica che `MY_IP` sia ancora il tuo IP attuale — se sei passato
  dalla connessione di casa al tethering 4G durante il lab, il SG ha l'IP
  vecchio.

## Wrap-up dell'aula

### Cosa abbiamo costruito

Un playbook Ansible che, in un singolo comando, crea un Security Group
restrittivo, lancia un'istanza EC2 Amazon Linux 2023 con AMI risolta a
runtime via SSM, configura la macchina con Java/Maven, clona e builda
Spring petclinic, lo avvia come servizio systemd e lo espone su porta 8080
— e che possiamo rieseguire ottenendo `changed=0`. È IaC end-to-end in
~150 righe di YAML.

### Le 3 cose da ricordare

- **Ansible Project Layout**: la separazione `inventory/` + `playbooks/` +
  `roles/` non è estetica, è la convenzione che rende il vostro lavoro
  compatibile con Galaxy, CI/CD e con il vostro stesso futuro.
- **Idempotenza del Playbook**: misurabile, non promessa. Un secondo run
  che ritorna `changed=0` è una prova; senza quel secondo run, "idempotente"
  è solo una parola sul curriculum.
- **Dynamic Inventory + add_host**: in cloud non gestite IP a mano. Tag
  per la discovery a regime, `add_host` per il bootstrap nello stesso run.
  Insieme eliminano i file `hosts` ricreati a mano da script bash.

### Domande aperte / link per approfondire

- **Ansible Vault** per le credenziali AWS — sostituire `lookup('env', ...)`
  con secret cifrati in repo.
- **Molecule** per testare il role `springboot` in isolamento (Docker o
  EC2 ephemeral), prima di toccare la produzione.
- **CI/CD del playbook**: GitHub Actions che esegue `ansible-playbook --check`
  (dry run) sui PR, e `ansible-playbook` reale su merge su `main`.
- **Multi-environment**: come strutturare `group_vars/staging/` e
  `group_vars/production/` per riusare lo stesso role in più ambienti.
- **`when: git_clone.changed`**: come migliorare Task 5 per saltare
  davvero Maven al secondo run (e gestire correttamente il caso "JAR già
  esistente" con un `stat` preventivo).

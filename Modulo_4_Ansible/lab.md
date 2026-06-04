---
lab_id: ansible-aws
version: 3
status: draft
generated_at: 2026-06-02T20:30:42.739054+00:00
based_on_evaluation: ./evaluation.md
---

# Deploy a Spring Boot Application on AWS EC2 with Ansible

## Learning Objectives

Al termine del lab sarai in grado di:

- Strutturare un progetto Ansible con `ansible.cfg`, inventory, playbook, ruoli.
- Provisionare un Security Group e un'istanza EC2 con i moduli `amazon.aws`.
- Usare il plugin di inventory dinamico `aws_ec2` per scoprire host taggati.
- Scrivere un role idempotente che installa la toolchain Java, clona un'app
  Spring Boot, la compila con Maven e la avvia come servizio `systemd`.
- Verificare end-to-end il deploy con `curl` sulla porta esposta dall'app.

## Prerequisites

### Tools required (with exact versions)

I comandi seguenti devono ritornare exit 0. Versioni minime indicate sotto:

```bash
# Python 3.9 o superiore — runtime di Ansible
python3 --version

# Ansible 2.16+ (ansible-core 2.16) — engine
ansible --version | head -1

# AWS CLI v2 — usata per verifiche e cleanup paralleli.
# Attenzione: `pip install awscli` installa la v1, non la v2.
aws --version

# OpenSSH client 8.0+ — usato implicitamente da Ansible per SSH
ssh -V

# jq — parsing JSON delle risposte AWS
jq --version

# curl — verifica HTTP dell'app deployata
curl --version | head -1
```

Se Ansible non è installato:

```bash
# ansible-core + dipendenze Python richieste dalla collection amazon.aws.
# boto3/botocore sono necessari ai moduli ec2_instance, ec2_security_group, aws_ec2 inventory.
python3 -m pip install --user "ansible-core>=2.16,<2.22" "boto3>=1.34" "botocore>=1.34"
```

Se la AWS CLI v2 non è installata, segui la procedura ufficiale per il tuo OS.
Il pacchetto pip `awscli` installa la v1, che ha incompatibilità minori sui
comandi `ssm get-parameter` e sull'output JSON.

```bash
# Linux x86_64 — installazione AWS CLI v2 da bundle ufficiale.
curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o /tmp/awscliv2.zip
unzip -q /tmp/awscliv2.zip -d /tmp
sudo /tmp/aws/install --update
rm -rf /tmp/awscliv2.zip /tmp/aws

# macOS (Homebrew) — alternativa per chi è su Mac.
# brew install awscli
```

Se OpenSSH client non è installato:

```bash
# Debian / Ubuntu
sudo apt-get update && sudo apt-get install -y openssh-client

# Amazon Linux / RHEL / Fedora
sudo dnf install -y openssh-clients

# macOS — preinstallato; verifica solo con `ssh -V`.
```

### Knowledge assumed

- YAML: indentazione significativa, liste e mapping.
- Concetto di idempotenza in Ansible (stesso playbook eseguito due volte non
  cambia lo stato dopo la prima esecuzione).
- Modello AWS di base: Region, VPC default, Security Group, Key Pair, EC2,
  AMI, SSM Parameter Store.
- Spring Boot produce un fat JAR eseguibile con `java -jar`.

## Setup

Prima di iniziare, configura l'ambiente di lavoro e verifica che le
credenziali AWS siano valide. Tutti i comandi del lab usano variabili
d'ambiente esportate qui, in modo che ogni snippet sia copia-incollabile.

> Importante: tutti i comandi successivi devono essere eseguiti nella **stessa
> sessione shell** in cui esegui questo blocco di Setup. Se apri un nuovo
> terminale, riesporta tutte le variabili.

```bash
# Region target — eu-west-1 è la default del lab.
# Cambiala se preferisci, ma assicurati che il Key Pair esista nella stessa region.
export AWS_DEFAULT_REGION=eu-west-1
export AWS_REGION="$AWS_DEFAULT_REGION"

# Credenziali AWS. Sostituisci con i tuoi valori reali — il lab NON può procedere
# senza credenziali valide perché crea risorse reali (EC2 + SG).
export AWS_ACCESS_KEY_ID="REPLACE_ME"
export AWS_SECRET_ACCESS_KEY="REPLACE_ME"

# Nome del Key Pair EC2 esistente nella region scelta.
# Crealo dalla console AWS o con `aws ec2 create-key-pair` se non ne hai uno.
export KEY_PAIR_NAME="ansible-lab-key"

# Path al file .pem locale corrispondente al Key Pair.
# Deve avere permessi 0600.
export SSH_KEY_PATH="$HOME/.ssh/${KEY_PAIR_NAME}.pem"

# Tag univoco che useremo per identificare le risorse del lab.
# Tutti i task creano risorse taggate workshop=ansible-lab per facilitare il cleanup.
export LAB_TAG="ansible-lab"

# Directory di lavoro del lab. Tutti i path successivi sono relativi a $LAB_DIR.
export LAB_DIR="$HOME/ansible-aws-lab"
mkdir -p "$LAB_DIR"
cd "$LAB_DIR"
```

Verifica che il Key Pair esista e che il file .pem locale sia leggibile.
Il Key Pair è una risorsa regionale: deve trovarsi nella stessa region
in cui lancerai l'EC2.

```bash
# La chiamata deve restituire il KeyName, non un errore InvalidKeyPair.NotFound.
aws ec2 describe-key-pairs --key-names "$KEY_PAIR_NAME" --query 'KeyPairs[0].KeyName' --output text

# Il file deve esistere e avere permessi restrittivi (SSH rifiuta chiavi 0644).
chmod 600 "$SSH_KEY_PATH"
test -r "$SSH_KEY_PATH" && echo "SSH key readable"
```

Il lab assume che esista il **Default VPC** nella region scelta: il modulo
`ec2_instance` userà la prima subnet della Default VPC quando non è specificato
`vpc_subnet_id`. Se hai eliminato o disabilitato il Default VPC, l'istanza
fallirà con un errore poco intuitivo. Verifica:

```bash
# Deve restituire un vpc-id (non 'None'). Se è 'None', crea il Default VPC con
# `aws ec2 create-default-vpc` oppure aggiungi `vpc_subnet_id` ai vars nel Task 4.
aws ec2 describe-vpcs --filters Name=isDefault,Values=true \
  --query 'Vpcs[0].VpcId' --output text
```

### Verifica setup

Esegui questo blocco prima di procedere. Se uno qualsiasi dei check
fallisce, ferma il lab e correggi l'ambiente.

```bash
# Sanity check: tool presenti, credenziali AWS valide, key pair esistente, lab dir creata,
# Default VPC presente nella region target.
set -e
command -v ansible-playbook >/dev/null
command -v aws >/dev/null
command -v ssh >/dev/null
command -v jq >/dev/null
aws sts get-caller-identity >/dev/null
aws ec2 describe-key-pairs --key-names "$KEY_PAIR_NAME" >/dev/null
DEFAULT_VPC=$(aws ec2 describe-vpcs --filters Name=isDefault,Values=true \
  --query 'Vpcs[0].VpcId' --output text)
[ "$DEFAULT_VPC" != "None" ] && [ -n "$DEFAULT_VPC" ]
test -d "$LAB_DIR"
test -r "$SSH_KEY_PATH"
echo "Setup OK"
set +e
```

## Task 1 — Scaffolding del progetto e configurazione Ansible

**Goal:** creare lo scheletro di directory, il file `ansible.cfg` e installare
la collection Ansible necessaria. Senza una `ansible.cfg` corretta i task
successivi non troveranno il role e useranno default poco verbose.

**Steps:**

Creiamo le cartelle del progetto. La separazione tra `inventory/`, `playbooks/`
e `roles/` è la convenzione standard Ansible: facilita il riuso e il versionamento
indipendente dei role.

```bash
cd "$LAB_DIR"
mkdir -p inventory playbooks roles/springboot/tasks roles/springboot/defaults roles/springboot/handlers
```

Creiamo `ansible.cfg` nella root del progetto. Ansible carica automaticamente
il file `ansible.cfg` presente nella current working directory, quindi tutti i
comandi `ansible-playbook` successivi vanno eseguiti da `$LAB_DIR`.

Il valore `roles_path = ./roles` è necessario perché i playbook si trovano in
`playbooks/` mentre i ruoli sono in `roles/`: senza questa direttiva Ansible
cerca in `playbooks/roles/` e non trova il role `springboot`.

Il valore `result_format = yaml` produce output YAML leggibile senza dipendere
dal plugin `community.general.yaml` (rimosso nella collection 12.0.0).

Impostiamo `deprecation_warnings = False` perché il plugin `amazon.aws.aws_ec2`
(versioni 11.x) emette un DEPRECATION WARNING interno sulla variabile host
`tags` anche quando l'inventory è configurato correttamente con `ec2_tags`.
Il warning è rumore di backward compatibility del plugin, non un problema della
nostra configurazione; disabilitarlo evita di confondere il learner.

**File to create:** `ansible.cfg`

```ini
[defaults]
inventory = ./inventory/aws_ec2.yml
roles_path = ./roles
host_key_checking = False
retry_files_enabled = False
stdout_callback = ansible.builtin.default
result_format = yaml
deprecation_warnings = False
forks = 10
timeout = 30

[inventory]
enable_plugins = amazon.aws.aws_ec2

[ssh_connection]
pipelining = True
ssh_args = -o ControlMaster=auto -o ControlPersist=60s -o StrictHostKeyChecking=no
```

Installiamo la collection. Le virgolette intorno ai vincoli di versione
sono obbligatorie: senza di esse la shell interpreta `>=7.0.0` come
redirect e crea file spurii nella working dir.

Usiamo solo `amazon.aws` (che contiene `ec2_instance`, `ec2_security_group`,
plugin inventory `aws_ec2`). Non installiamo `community.general` perché il lab
non ne usa alcun modulo: meno download, meno superficie di compatibilità.

```bash
# I vincoli devono essere quotati per evitare che bash interpreti `>=` come redirect.
ansible-galaxy collection install "amazon.aws:>=7.0.0,<12.0.0"
```

**Expected outcome:** la struttura del progetto è in place, `ansible.cfg` è
nella root, la collection `amazon.aws` è installata in `~/.ansible/collections`.
Nessun file spurio `=*` nella working dir.

**Success criterion:**

```bash
cd "$LAB_DIR" \
  && test -f ansible.cfg \
  && test -d roles/springboot/tasks \
  && test -d inventory \
  && test -d playbooks \
  && ! ls -1 "$LAB_DIR" | grep -E '^=' \
  && ansible-galaxy collection list amazon.aws 2>/dev/null | grep -q "amazon.aws"
```

## Task 2 — Inventory dinamico tramite plugin `aws_ec2`

**Goal:** configurare il plugin di inventory dinamico che interroga EC2 e
restituisce gli host taggati `workshop=ansible-lab`. Usiamo l'inventory
dinamico (invece di una lista statica di IP) perché l'IP pubblico dell'EC2
non è noto al momento della scrittura del playbook.

**Steps:**

Il file deve chiamarsi con il suffisso `aws_ec2.yml` perché è il pattern
riconosciuto dal plugin. La chiave `keyed_groups` raggruppa gli host per tag,
producendo gruppi tipo `tag_ansible_lab_instance`.

Usiamo `ec2_tags.Name` invece di `tags.Name`: la variabile `tags` è deprecata
in `amazon.aws` ≥ 8 e verrà rimossa dopo dicembre 2026.

**File to create:** `inventory/aws_ec2.yml`

```yaml
---
plugin: amazon.aws.aws_ec2
regions:
  - eu-west-1
filters:
  tag:workshop: ansible-lab
  instance-state-name:
    - pending
    - running
keyed_groups:
  - key: ec2_tags.Name
    prefix: tag
hostnames:
  - ip-address
compose:
  ansible_host: public_ip_address
```

Verifichiamo che il plugin riesca a parsare il file. A questo stadio non
esistono ancora istanze EC2 taggate: l'output deve mostrare il gruppo `@all`
senza errori di parsing.

```bash
cd "$LAB_DIR"
ansible-inventory --graph
```

**Expected outcome:** `ansible-inventory --graph` mostra `@all:` e nessun
messaggio `Failed to parse`. Eventuali `AuthFailure` qui indicano credenziali
AWS errate, non un problema di sintassi del file inventory. Il DEPRECATION
WARNING sulla variabile `tags` emesso da versioni recenti del plugin è
interno al plugin (backward compat) e non riguarda la tua configurazione:
in `ansible.cfg` lo abbiamo silenziato con `deprecation_warnings = False`.

**Success criterion:**

```bash
cd "$LAB_DIR" \
  && OUT=$(ansible-inventory --graph 2>&1) \
  && echo "$OUT" | grep -q "^@all:" \
  && ! echo "$OUT" | grep -q "Failed to parse" \
  && ! echo "$OUT" | grep -qi "AuthFailure"
```

## Task 3 — Provisioning del Security Group

**Goal:** creare un Security Group che permetta SSH (porta 22) e HTTP sulla
porta 8080 esclusivamente dal tuo IP pubblico corrente. Esporre 8080 a
`0.0.0.0/0` è una pratica insicura: limitiamo l'accesso al solo IP del
control node.

**Steps:**

Scriviamo un playbook dedicato al Security Group. È utile separare il
provisioning di rete dal provisioning compute perché il SG sopravvive
all'istanza e può essere riusato.

**File to create:** `playbooks/security_group.yml`

```yaml
---
- name: Provision lab security group
  hosts: localhost
  gather_facts: false
  vars:
    sg_name: ansible-lab-sg
    sg_description: Security group for ansible-aws lab (SSH + HTTP 8080 from control node IP)
    my_ip: "{{ lookup('env', 'MY_IP') }}"
    lab_tag: "{{ lookup('env', 'LAB_TAG') }}"
  tasks:
    - name: Ensure security group exists with required ingress rules
      amazon.aws.ec2_security_group:
        name: "{{ sg_name }}"
        description: "{{ sg_description }}"
        rules:
          - proto: tcp
            ports: 22
            cidr_ip: "{{ my_ip }}"
            rule_desc: SSH from control node
          - proto: tcp
            ports: 8080
            cidr_ip: "{{ my_ip }}"
            rule_desc: Spring Boot HTTP from control node
        rules_egress:
          - proto: -1
            cidr_ip: 0.0.0.0/0
            rule_desc: Allow all outbound
        tags:
          workshop: "{{ lab_tag }}"
          Name: "{{ sg_name }}"
        state: present
      register: sg_result

    - name: Print security group id
      ansible.builtin.debug:
        msg: "Security Group ID: {{ sg_result.group_id }}"
```

Recuperiamo l'IP pubblico del control node, eseguiamo il playbook e salviamo
l'ID del Security Group, tutto nella **stessa sessione shell** in cui hai
eseguito il Setup. Tieni tutto in un unico blocco per evitare che `MY_IP`
risulti vuota se apri un nuovo terminale tra uno step e l'altro.

```bash
cd "$LAB_DIR"

# 1. Recupera l'IP pubblico corrente e usalo come CIDR /32 nelle regole del SG.
#    Se sei dietro NAT/VPN questo è l'IP visto da AWS, non quello della tua macchina.
export MY_IP="$(curl -fsS https://checkip.amazonaws.com)/32"
echo "Your public IP: $MY_IP"

# 2. Provisioning del Security Group. Idempotente: rieseguirlo non duplica regole.
ansible-playbook playbooks/security_group.yml

# 3. Salva l'ID del SG in una variabile d'ambiente per i task successivi.
#    La query JMESPath cerca il SG col tag workshop=ansible-lab.
export LAB_SG_ID=$(aws ec2 describe-security-groups \
  --filters "Name=tag:workshop,Values=$LAB_TAG" \
  --query 'SecurityGroups[0].GroupId' \
  --output text)
echo "Security Group ID: $LAB_SG_ID"
```

**Expected outcome:** il Security Group `ansible-lab-sg` esiste nella region,
ha due regole ingress (22 e 8080 dal tuo IP) e una regola egress permissiva.
La variabile `LAB_SG_ID` contiene un ID `sg-...`.

**Success criterion:**

```bash
test -n "$LAB_SG_ID" \
  && [ "$LAB_SG_ID" != "None" ] \
  && aws ec2 describe-security-groups --group-ids "$LAB_SG_ID" \
       --query 'SecurityGroups[0].IpPermissions[?ToPort==`8080`] | length(@)' \
       --output text | grep -q '^1$'
```

## Task 4 — Playbook di provisioning EC2

**Goal:** scrivere il playbook che lancia l'istanza EC2, attende che SSH
sia disponibile e la aggiunge a un gruppo in-memory così che la seconda
play possa applicare il role `springboot`.

**Steps:**

L'AMI ID di Amazon Linux 2023 è specifico per region e cambia nel tempo.
Lo risolviamo a runtime via SSM Parameter Store: AWS pubblica e mantiene
aggiornato un parametro pubblico con l'ID dell'AMI più recente. Questo
evita di hardcodare un ID che potrebbe diventare obsoleto.

Prima di scrivere il playbook completo, creiamo uno stub del role `springboot`.
Il `--syntax-check` nel passo successivo richiede che il role sia risolvibile:
popoleremo i task reali nel Task 5.

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

**File to create:** `playbooks/deploy.yml`

```yaml
---
- name: Provision EC2 instance for Spring Boot lab
  hosts: localhost
  gather_facts: false
  vars:
    instance_name: ansible-lab-instance
    instance_type: t3.micro
    key_pair: "{{ lookup('env', 'KEY_PAIR_NAME') }}"
    ssh_key_path: "{{ lookup('env', 'SSH_KEY_PATH') }}"
    sg_id: "{{ lookup('env', 'LAB_SG_ID') }}"
    lab_tag: "{{ lookup('env', 'LAB_TAG') }}"
    region: "{{ lookup('env', 'AWS_DEFAULT_REGION') }}"
    al2023_ssm_param: /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64
  tasks:
    - name: Resolve latest Amazon Linux 2023 AMI id via SSM public parameter
      ansible.builtin.command:
        cmd: >-
          aws ssm get-parameter --name {{ al2023_ssm_param }}
          --region {{ region }} --query Parameter.Value --output text
      register: ami_lookup
      changed_when: false

    - name: Set AMI id fact
      ansible.builtin.set_fact:
        al2023_ami_id: "{{ ami_lookup.stdout | trim }}"

    - name: Launch EC2 instance
      amazon.aws.ec2_instance:
        name: "{{ instance_name }}"
        key_name: "{{ key_pair }}"
        instance_type: "{{ instance_type }}"
        image_id: "{{ al2023_ami_id }}"
        security_groups:
          - "{{ sg_id }}"
        network:
          assign_public_ip: true
        tags:
          workshop: "{{ lab_tag }}"
          Name: "{{ instance_name }}"
        wait: true
        state: running
      register: ec2

    - name: Capture public IP fact
      ansible.builtin.set_fact:
        instance_public_ip: "{{ ec2.instances[0].public_ip_address }}"

    - name: Wait for SSH to become reachable
      ansible.builtin.wait_for:
        host: "{{ instance_public_ip }}"
        port: 22
        timeout: 180
        state: started

    - name: Add instance to in-memory inventory group
      ansible.builtin.add_host:
        hostname: "{{ instance_public_ip }}"
        groups: app_servers
        ansible_user: ec2-user
        ansible_ssh_private_key_file: "{{ ssh_key_path }}"
        ansible_ssh_common_args: -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null

    - name: Print connection info
      ansible.builtin.debug:
        msg: "EC2 ready at {{ instance_public_ip }} — proceeding to deploy"

- name: Deploy Spring Boot application on EC2
  hosts: app_servers
  become: true
  gather_facts: true
  roles:
    - springboot
```

Verifichiamo la sintassi del playbook senza eseguirlo. `--syntax-check`
non chiama AWS: valida solo struttura YAML e risoluzione dei ruoli.

```bash
cd "$LAB_DIR"
ansible-playbook playbooks/deploy.yml --syntax-check
```

**Expected outcome:** `--syntax-check` ritorna exit 0 e non segnala
"role not found". Se vedi "The role 'springboot' was not found", controlla
che `roles_path = ./roles` sia in `ansible.cfg` e che tu stia eseguendo il
comando da `$LAB_DIR`.

**Success criterion:**

```bash
cd "$LAB_DIR" \
  && test -f playbooks/deploy.yml \
  && python3 -c "import yaml; yaml.safe_load(open('playbooks/deploy.yml'))" \
  && ansible-playbook playbooks/deploy.yml --syntax-check >/dev/null 2>&1
```

## Task 5 — Role `springboot`: installa toolchain, clona, builda, avvia

**Goal:** scrivere il role che, eseguito su un host Amazon Linux 2023,
installa Java 21 + Git + Maven, clona un'applicazione Spring Boot,
la compila e la avvia come servizio `systemd`. Usare systemd (anziché
`nohup`) garantisce che l'app sopravviva al reboot e abbia log gestiti
via journald.

**Steps:**

Definiamo i default del role. Variabili separate per repository e branch
permettono di sostituire l'applicazione senza modificare il codice del role.

**File to create:** `roles/springboot/defaults/main.yml`

```yaml
---
# URL del repository Spring Boot da deployare.
# spring-petclinic è una demo app ufficiale Spring, builda con Maven, espone porta 8080.
springboot_repo_url: https://github.com/spring-projects/spring-petclinic.git
springboot_repo_version: main

# Path locale sull'host EC2 dove clonare il sorgente.
springboot_install_dir: /opt/springboot-app

# Nome del servizio systemd.
springboot_service_name: springboot-app

# Utente di sistema che possiede i file ed esegue il servizio.
springboot_user: springboot

# Porta esposta dall'applicazione. Spring Boot di default usa 8080.
springboot_port: 8080
```

Scriviamo il task principale del role. Sovrascriviamo il file `main.yml`
creato come stub nel Task 4.

Nota sull'idempotenza della build Maven: usiamo `changed_when: git_clone.changed`
in modo che il task sia segnato come "changed" solo quando il clone Git ha
effettivamente aggiornato i sorgenti. Una condizione tipo
`'BUILD SUCCESS' in mvn_build.stdout` sarebbe **sempre vera** quando Maven
ha successo e farebbe risultare la build "changed" anche al secondo run senza
modifiche al codice — rompendo l'idempotenza dichiarata nei Learning Objectives.

**File to create:** `roles/springboot/tasks/main.yml`

```yaml
---
# Spring Boot petclinic richiede Java 17+; usiamo Corretto 21 (LTS, supportato da AWS).
- name: Install Java 21, Git and Maven
  ansible.builtin.dnf:
    name:
      - java-21-amazon-corretto-headless
      - git
      - maven
    state: present
    update_cache: true

# Utente dedicato — buona pratica per non eseguire l'app come root.
- name: Ensure service user exists
  ansible.builtin.user:
    name: "{{ springboot_user }}"
    system: true
    shell: /sbin/nologin
    create_home: true
    home: "/home/{{ springboot_user }}"
    state: present

- name: Ensure install directory exists
  ansible.builtin.file:
    path: "{{ springboot_install_dir }}"
    state: directory
    owner: "{{ springboot_user }}"
    group: "{{ springboot_user }}"
    mode: "0755"

# Clone idempotente: la task non riclona se HEAD è già su springboot_repo_version.
- name: Clone Spring Boot application repository
  ansible.builtin.git:
    repo: "{{ springboot_repo_url }}"
    dest: "{{ springboot_install_dir }}"
    version: "{{ springboot_repo_version }}"
    force: true
  become_user: "{{ springboot_user }}"
  register: git_clone

# Maven wrapper se presente nel repo, altrimenti mvn di sistema.
# -DskipTests perché in lab non vogliamo allungare il tempo di build.
- name: Detect maven wrapper presence
  ansible.builtin.stat:
    path: "{{ springboot_install_dir }}/mvnw"
  register: mvnw_stat

- name: Build application JAR
  ansible.builtin.command:
    cmd: "{{ (mvnw_stat.stat.exists | bool) | ternary('./mvnw', 'mvn') }} -B -DskipTests package"
    chdir: "{{ springboot_install_dir }}"
  become_user: "{{ springboot_user }}"
  environment:
    HOME: "/home/{{ springboot_user }}"
    MAVEN_OPTS: -Xmx512m
  register: mvn_build
  # Idempotenza: la build è "changed" solo se il clone Git ha aggiornato i sorgenti.
  # Senza questa condizione, ogni run di playbook segnalerebbe la build come "changed".
  changed_when: git_clone.changed

# Cerchiamo il fat JAR generato. Escludiamo sources/javadoc/original per
# evitare di selezionare artefatti non eseguibili.
- name: Locate produced fat JAR
  ansible.builtin.find:
    paths: "{{ springboot_install_dir }}/target"
    patterns: "*.jar"
    excludes:
      - "*-sources.jar"
      - "*-javadoc.jar"
      - "original-*.jar"
  register: jar_files

- name: Fail if no JAR was produced
  ansible.builtin.fail:
    msg: "Maven build did not produce a runnable JAR in target/"
  when: jar_files.matched == 0

- name: Resolve JAR path fact
  ansible.builtin.set_fact:
    springboot_jar_path: "{{ (jar_files.files | sort(attribute='path') | last).path }}"

# Service unit systemd — gestisce restart on failure e log via journald.
# notify -> handler "reload systemd": daemon-reload solo se il file è effettivamente
# cambiato. Non duplichiamo l'azione con un task esplicito.
- name: Install systemd unit for Spring Boot service
  ansible.builtin.copy:
    dest: "/etc/systemd/system/{{ springboot_service_name }}.service"
    owner: root
    group: root
    mode: "0644"
    content: |
      [Unit]
      Description=Spring Boot application ({{ springboot_service_name }})
      After=network-online.target
      Wants=network-online.target

      [Service]
      Type=simple
      User={{ springboot_user }}
      WorkingDirectory={{ springboot_install_dir }}
      ExecStart=/usr/bin/java -jar {{ springboot_jar_path }} --server.port={{ springboot_port }}
      Restart=on-failure
      RestartSec=5
      SuccessExitStatus=143

      [Install]
      WantedBy=multi-user.target
  notify: reload systemd

# Flush degli handler PRIMA di start: garantisce che `daemon_reload` avvenga
# (se notificato) prima che systemd provi a leggere la unit. Senza meta:flush_handlers
# gli handler verrebbero eseguiti solo a fine play, dopo lo `Start service`.
- name: Flush handlers so systemd is reloaded before service start
  ansible.builtin.meta: flush_handlers

- name: Enable and start Spring Boot service
  ansible.builtin.systemd:
    name: "{{ springboot_service_name }}"
    enabled: true
    state: started

# Attendiamo che la porta sia in LISTEN prima di considerare il deploy completo.
- name: Wait for application port to accept connections
  ansible.builtin.wait_for:
    host: 127.0.0.1
    port: "{{ springboot_port }}"
    timeout: 180
    state: started
```

Aggiungiamo l'handler `reload systemd` referenziato sopra. Gli handler
sono task speciali che vengono eseguiti **una sola volta** alla fine della
play (o al `meta: flush_handlers`) anche se notificati da più task. Qui
viene notificato dalla copia del file `.service`: se la unit non cambia,
`daemon_reload` non viene eseguito — è il comportamento idempotente che
vogliamo.

**File to create:** `roles/springboot/handlers/main.yml`

```yaml
---
- name: reload systemd
  ansible.builtin.systemd:
    daemon_reload: true
```

Verifichiamo la sintassi finale del playbook ora che il role è completo.

```bash
cd "$LAB_DIR"
ansible-playbook playbooks/deploy.yml --syntax-check
```

**Expected outcome:** la struttura del role è completa, syntax-check exit 0.
Al secondo run del playbook (Task 6), il task `Build application JAR` deve
risultare `ok` (non `changed`) perché `git_clone.changed` è `false`.

**Success criterion:**

```bash
cd "$LAB_DIR" \
  && test -f roles/springboot/tasks/main.yml \
  && test -f roles/springboot/defaults/main.yml \
  && test -f roles/springboot/handlers/main.yml \
  && python3 -c "import yaml; yaml.safe_load(open('roles/springboot/tasks/main.yml'))" \
  && python3 -c "import yaml; yaml.safe_load(open('roles/springboot/defaults/main.yml'))" \
  && python3 -c "import yaml; yaml.safe_load(open('roles/springboot/handlers/main.yml'))" \
  && ! grep -q "Placeholder" roles/springboot/tasks/main.yml \
  && grep -q "changed_when: git_clone.changed" roles/springboot/tasks/main.yml \
  && ansible-playbook playbooks/deploy.yml --syntax-check >/dev/null 2>&1
```

## Task 6 — Esecuzione end-to-end e verifica HTTP

**Goal:** eseguire il playbook completo, attendere il deploy, verificare
con `curl` che l'applicazione risponda sulla porta 8080 dell'istanza
pubblica. Verificheremo anche l'idempotenza con un secondo run.

**Steps:**

Eseguiamo il playbook con `-v` per vedere il dettaglio di ogni task. Il
tempo totale tipico è 4–7 minuti (provisioning EC2: ~60s, install pacchetti:
~30s, clone: ~10s, build maven petclinic: ~3-5min, avvio servizio: ~30s).

```bash
cd "$LAB_DIR"
ansible-playbook playbooks/deploy.yml -v
```

Recuperiamo l'IP pubblico dell'istanza creata. Usiamo la AWS CLI direttamente
(anziché l'inventory dinamico) perché vogliamo un valore deterministico anche
in presenza di cache dell'inventory.

```bash
export INSTANCE_PUBLIC_IP=$(aws ec2 describe-instances \
  --filters "Name=tag:workshop,Values=$LAB_TAG" "Name=instance-state-name,Values=running" \
  --query 'Reservations[0].Instances[0].PublicIpAddress' \
  --output text)
echo "Instance public IP: $INSTANCE_PUBLIC_IP"
```

Verifichiamo l'app. Spring petclinic risponde HTTP 200 sulla root `/`
con la home page HTML. Usiamo `curl -f` per fallire su status >= 400 e
applichiamo un retry per dare tempo alla JVM di completare il warm-up.

```bash
# Retry per dare tempo al servizio di completare l'avvio della JVM e il warm-up Spring.
for attempt in 1 2 3 4 5 6; do
  if curl -fsS --max-time 10 "http://${INSTANCE_PUBLIC_IP}:8080/" -o /tmp/lab_response.html; then
    echo "App responded on attempt $attempt"
    break
  fi
  echo "Attempt $attempt failed, retrying in 20s..."
  sleep 20
done
```

Ispezioniamo brevemente il contenuto per conferma visiva:

```bash
# Petclinic genera HTML con il tag <title>PetClinic ...</title>.
head -c 500 /tmp/lab_response.html
```

Verifichiamo l'idempotenza. Una seconda esecuzione del playbook su uno stato
già convergente deve completare con `changed=0` per la play di deploy
(l'istanza esiste già, il SG è già configurato, il codice non è cambiato,
il servizio è già attivo).

```bash
cd "$LAB_DIR"
ansible-playbook playbooks/deploy.yml | tee /tmp/lab_second_run.txt
```

**Expected outcome:** `curl` restituisce HTTP 200 e il contenuto include
una stringa identificativa dell'applicazione (`PetClinic` se hai usato il
repo di default). La seconda esecuzione del playbook riporta `changed=0`
sulla play `Deploy Spring Boot application on EC2`.

**Success criterion:**

```bash
test -n "$INSTANCE_PUBLIC_IP" \
  && [ "$INSTANCE_PUBLIC_IP" != "None" ] \
  && [ "$(curl -fsS --max-time 10 -o /dev/null -w '%{http_code}' http://${INSTANCE_PUBLIC_IP}:8080/)" = "200" ] \
  && grep -E "app_servers.*changed=0" /tmp/lab_second_run.txt
```

## Cleanup

Rimuoviamo nell'ordine: istanza EC2 → Security Group (può essere eliminato
solo quando nessuna istanza vi è associata) → directory locale. Tutte le
risorse AWS sono identificabili dal tag `workshop=ansible-lab`.

```bash
# 1. Termina tutte le istanze taggate workshop=ansible-lab nella region corrente.
INSTANCE_IDS=$(aws ec2 describe-instances \
  --filters "Name=tag:workshop,Values=$LAB_TAG" \
            "Name=instance-state-name,Values=pending,running,stopping,stopped" \
  --query 'Reservations[].Instances[].InstanceId' \
  --output text)

if [ -n "$INSTANCE_IDS" ]; then
  aws ec2 terminate-instances --instance-ids $INSTANCE_IDS >/dev/null
  echo "Terminating: $INSTANCE_IDS"
  aws ec2 wait instance-terminated --instance-ids $INSTANCE_IDS
  echo "Instances terminated"
fi

# 2. Elimina il Security Group del lab. Possibile solo dopo terminazione delle istanze.
SG_IDS=$(aws ec2 describe-security-groups \
  --filters "Name=tag:workshop,Values=$LAB_TAG" \
  --query 'SecurityGroups[].GroupId' \
  --output text)

for sg in $SG_IDS; do
  aws ec2 delete-security-group --group-id "$sg" && echo "Deleted SG $sg"
done

# 3. Rimuovi la directory locale del lab.
cd "$HOME"
rm -rf "$LAB_DIR"
echo "Local lab directory removed"

# 4. Rimuovi file temporanei creati durante la verifica.
rm -f /tmp/lab_response.html /tmp/lab_second_run.txt

# 5. Unset variabili d'ambiente del lab (igiene di shell).
unset KEY_PAIR_NAME SSH_KEY_PATH LAB_TAG LAB_DIR LAB_SG_ID MY_IP INSTANCE_PUBLIC_IP
echo "Cleanup complete"
```

Verifica finale che nulla sia rimasto:

```bash
# Devono restituire 0 istanze e 0 security group residui col tag del lab.
aws ec2 describe-instances \
  --filters "Name=tag:workshop,Values=ansible-lab" \
            "Name=instance-state-name,Values=pending,running,stopping,stopped" \
  --query 'length(Reservations[].Instances[])' --output text
aws ec2 describe-security-groups \
  --filters "Name=tag:workshop,Values=ansible-lab" \
  --query 'length(SecurityGroups)' --output text
```

## Troubleshooting

### `[ERROR]: The 'community.general.yaml' callback plugin has been removed`

Stai usando una versione di `community.general` ≥ 12.0.0 con un
`ansible.cfg` che imposta `stdout_callback = yaml`. Il plugin è stato
rimosso. Verifica che `ansible.cfg` usi:

```ini
stdout_callback = ansible.builtin.default
result_format = yaml
```

### `[ERROR]: The role 'springboot' was not found`

`ansible.cfg` non contiene `roles_path = ./roles`, oppure stai eseguendo
`ansible-playbook` da una directory diversa da `$LAB_DIR`. Verifica con
`pwd` e con `grep roles_path ansible.cfg`.

### `Failed to parse inventory ... AuthFailure`

Le credenziali AWS non sono valide o non sono esportate nella shell
corrente. Riesegui i `export AWS_ACCESS_KEY_ID=...` e verifica con
`aws sts get-caller-identity`.

### `DEPRECATION WARNING: The 'tags' host variable is deprecated`

È un warning interno del plugin `amazon.aws.aws_ec2` (≥ 11.x) e non
indica un errore della tua configurazione. L'abbiamo silenziato in
`ansible.cfg` con `deprecation_warnings = False`. Se lo vedi comunque,
controlla di non aver dimenticato quella riga sotto `[defaults]`.

### `InvalidKeyPair.NotFound`

Il Key Pair indicato in `KEY_PAIR_NAME` non esiste nella region
`AWS_DEFAULT_REGION`. I Key Pair sono regionali: creane uno nella region
del lab con:

```bash
aws ec2 create-key-pair --key-name "$KEY_PAIR_NAME" \
  --query KeyMaterial --output text > "$SSH_KEY_PATH"
chmod 600 "$SSH_KEY_PATH"
```

### `Permissions 0644 for '...pem' are too open`

SSH rifiuta chiavi private leggibili da altri utenti.
`chmod 600 "$SSH_KEY_PATH"`.

### `VPCIdNotSpecified` o `No default subnet for availability zone`

Hai eliminato o disabilitato il Default VPC nella region target. Ricrealo
con `aws ec2 create-default-vpc`, oppure aggiungi una variabile
`vpc_subnet_id: subnet-xxxx` nei vars del Task 4 e passala al modulo
`ec2_instance` con `vpc_subnet_id: "{{ vpc_subnet_id }}"`.

### `wait_for` su porta 8080 va in timeout

L'app non è partita. Connettiti via SSH all'istanza e ispeziona i log
con `sudo journalctl -u springboot-app -n 200`. Cause tipiche: build
maven fallita (`ls /opt/springboot-app/target/`), porta già occupata
(`sudo ss -tlnp | grep 8080`), Java non installato (`java -version`).

### File `=7.0.0` o simili creati in `$LAB_DIR`

Hai eseguito `ansible-galaxy collection install` senza quotare i vincoli
di versione. La shell ha interpretato `>=` come redirect. Rimuovi i file
(`rm "$LAB_DIR"/=*`) e riesegui il comando con le virgolette come mostrato
nel Task 1.

### `Could not find imported module support code for amazon.aws.ec2_instance`

`boto3`/`botocore` non sono installati nell'interprete Python usato da
Ansible. Verifica con `ansible --version | grep 'python version'` quale
interprete Ansible sta usando, poi installa le dipendenze in quell'ambiente:
`python3 -m pip install --user "boto3>=1.34" "botocore>=1.34"`.

### Secondo run del playbook segnala `Build application JAR` come `changed`

Significa che il task `Clone Spring Boot application repository` ha
aggiornato i sorgenti — o perché `force: true` sta sovrascrivendo modifiche
locali, o perché il branch remoto è effettivamente avanzato. Verifica con
`git -C /opt/springboot-app status` e `git -C /opt/springboot-app log -1`
sull'istanza EC2.

### `MY_IP` è vuota quando esegui `playbooks/security_group.yml`

Hai aperto un nuovo terminale tra il blocco di Setup e l'esecuzione del
playbook. Riesegui nello stesso terminale:

```bash
export MY_IP="$(curl -fsS https://checkip.amazonaws.com)/32"
```

e ri-esporta anche `LAB_TAG` e le credenziali AWS se necessario.

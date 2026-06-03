---
lab_id: ansible-aws
lab_version: 3
evaluator_run_id: 2026-06-02T20:34:51.324069+00:00
overall_status: NEEDS_REVISION
iterations_remaining: 2
total_duration_seconds: 480
---

# Evaluation Report

## Setup
**Status:** BLOCKED (ambiente — non lab defect)

**Notes:**
Il Setup richiede credenziali AWS valide (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`) e un
Key Pair EC2 preesistente. Nel container di valutazione nessuna credenziale è disponibile;
`aws sts get-caller-identity` e `aws ec2 describe-key-pairs` falliscono con `AuthFailure`.
Questo è un vincolo dell'ambiente di valutazione, non un difetto del lab.

Nonostante il blocco AWS, sono stati verificati tutti i componenti non-AWS del lab:
scaffolding, file creation, syntax-check dei playbook (Tasks 1, 4, 5) — tutti PASS.

| Tool | Richiesto | Trovato nel container |
|------|-----------|----------------------|
| `python3` | ≥ 3.9 | Python 3.14.4 ✓ |
| `ansible-playbook` | ≥ 2.16 | Non preinstallato — installato via pip con `--break-system-packages` (vedi BLOCKING #2) |
| `aws` CLI | v2 | Non preinstallato — installato tramite bundle ufficiale ✓ |
| `ssh` client | OpenSSH ≥ 8.0 | Non preinstallato — installato via `apt-get install openssh-client` ✓ |
| `jq` | qualsiasi | 1.8.1 ✓ |
| `curl` | qualsiasi | 8.18.0 ✓ |

**Problema rilevato nella procedura di installazione Ansible (vedi BLOCKING item #2):**
Su Ubuntu 26.04 / Python 3.14, il comando `python3 -m pip install --user ...` del lab
fallisce con `externally-managed-environment` (PEP 668). Il lab non menziona
`--break-system-packages` né l'uso di `venv`/`pipx`.

**Rispetto alla v2:** il bug BLOCKING `changed_when: git_clone.changed or 'BUILD SUCCESS' in mvn_build.stdout`
è stato corretto — ora la task usa correttamente solo `changed_when: git_clone.changed` ✓.
Il doppio daemon_reload è stato eliminato (handler-only approach) ✓.
`community.general` rimossa dall'install command ✓.
`deprecation_warnings = False` aggiunto in ansible.cfg ✓.

---

## Task 1 — Scaffolding del progetto e configurazione Ansible
**Status:** PASS
**Time taken:** ~90s (include download collection amazon.aws 11.3.0 da Galaxy)

**Actual output:**
```
Starting galaxy collection install process
Downloading ...amazon-aws-11.3.0.tar.gz
Installing 'amazon.aws:11.3.0' to '/root/.ansible/collections/ansible_collections/amazon/aws'
amazon.aws:11.3.0 was installed successfully

ansible.cfg: OK
roles/springboot/tasks: OK
inventory: OK
playbooks: OK
no spurious '=' files: OK
amazon.aws collection: OK
Exit: 0
```

**Note:** Il primo tentativo di `ansible-galaxy collection install` ha ricevuto HTTP 502
(errore transiente di Galaxy). Il secondo tentativo ha avuto successo. Non è un problema del
lab ma può confondere lo studente; un retry automatico o flag `--timeout` aiuterebbe.

---

## Task 2 — Inventory dinamico tramite plugin `aws_ec2`
**Status:** FAIL (per assenza di credenziali AWS valide — non lab defect)
**Time taken:** ~3s

**Actual output:**
```
[WARNING]: Failed to parse inventory with 'ansible_collections.amazon.aws.plugins.inventory.aws_ec2'
           plugin: Failed to describe instances: An error occurred (AuthFailure) ...
[WARNING]: Unable to parse .../inventory/aws_ec2.yml as an inventory source
[WARNING]: No inventory was parsed, only implicit localhost is available
@all:
  |--@ungrouped:
```

**Root cause analysis:**
Il plugin `aws_ec2` tenta una chiamata reale a `ec2:DescribeInstances` durante
`ansible-inventory --graph`. Con credenziali non valide restituisce `AuthFailure`,
causando "Failed to parse" nell'output — il che fa fallire il success criterion
(`! grep -q "Failed to parse"` e `! grep -qi "AuthFailure"`).
Con credenziali AWS valide il success criterion passerebbe correttamente.

La struttura del file `inventory/aws_ec2.yml` è sintatticamente corretta; `ec2_tags.Name`
è appropriato per amazon.aws ≥ 8 ✓. Il DEPRECATION WARNING sulla variabile `tags` è
silenzianato da `deprecation_warnings = False` in ansible.cfg ✓ (nessun warning visibile
in output).

---

## Task 3 — Provisioning del Security Group
**Status:** SKIPPED (dipende da credenziali AWS valide — Setup BLOCKED)
**Time taken:** N/A

**Valutazione contenuto statico:**
- YAML `playbooks/security_group.yml`: valido ✓
- `ansible-playbook --syntax-check`: exit 0 ✓
- `proto: -1` per egress (all protocols): corretto ✓
- `sg_result.group_id`: chiave di ritorno corretta ✓
- `export MY_IP="$(curl -fsS https://checkip.amazonaws.com)/32"`: corretto ✓

---

## Task 4 — Playbook di provisioning EC2
**Status:** PASS
**Time taken:** ~4s

**Actual output:**
```
[WARNING]: Failed to parse inventory ... Unable to locate credentials [suppresso da >/dev/null 2>&1]
playbook: playbooks/deploy.yml    (exit 0)

YAML valid
syntax-check: PASS
Exit: 0
```

**WARNING — Documentazione imprecisa su `--syntax-check`:**
Il lab afferma: *"`--syntax-check` non chiama AWS: valida solo struttura YAML e risoluzione dei ruoli."*
In realtà il comando impiega ~3.4s e fa una chiamata a `ec2:DescribeInstances` tramite il
plugin di inventory `aws_ec2`. Il success criterion nasconde il problema con `>/dev/null 2>&1`
(exit 0 anche con AuthFailure), ma la spiegazione è fuorviante per lo studente.

---

## Task 5 — Role `springboot`: installa toolchain, clona, builda, avvia
**Status:** PASS
**Time taken:** ~4s

**Actual output:**
```
tasks/main.yml: OK
defaults/main.yml: OK
handlers/main.yml: OK
tasks YAML: OK
defaults YAML: OK
handlers YAML: OK
No Placeholder: OK
changed_when: OK
syntax-check: PASS
Exit: 0
```

**Note positive rispetto alla v2:**
- `changed_when: git_clone.changed` (senza `or 'BUILD SUCCESS'...`): corretto ✓
- Handler-only approach per `daemon_reload`: pulito ✓
- `meta: flush_handlers` prima di `Enable and start service`: corretto ✓
- `MAVEN_OPTS: -Xmx512m`: previene OOM su t3.micro ✓
- `SuccessExitStatus=143` nel service unit: corretto per JVM/SIGTERM ✓
- `become_user: springboot` per clone e build: least-privilege ✓

**Nota:** Il task `Build application JAR` usa `changed_when: git_clone.changed` ma NON
`when: git_clone.changed`, quindi Maven viene rieseguito (compilazione lenta, 3-5 min) ad
ogni run del playbook anche se niente è cambiato. Il `changed_when` controlla solo il
reporting, non l'esecuzione. Il lab documenta esplicitamente questa scelta di design (la
spiega nella prosa del Task 5), quindi è accettabile come WARNING, non FAIL.

---

## Task 6 — Esecuzione end-to-end e verifica HTTP
**Status:** SKIPPED (dipende da EC2 running — Setup BLOCKED)
**Time taken:** N/A

**Bug rilevato nel success criterion — BLOCKING:**

```bash
grep -E "app_servers.*changed=0" /tmp/lab_second_run.txt
```

Questo pattern **non matcherà mai** l'output reale di Ansible. Il PLAY RECAP mostra l'indirizzo
IP dell'host (es. `54.195.10.23`), **non** il nome del gruppo inventory (`app_servers`):

```
PLAY RECAP *****
54.195.10.23   : ok=12   changed=0    unreachable=0    failed=0    skipped=0    rescued=0    ignored=0
```

Confermato con simulazione: il pattern `app_servers.*changed=0` non produce alcun match
su output PLAY RECAP realistico. Lo studente vedrebbe fallire la verifica dell'idempotenza
anche con un deploy perfetto.

**Fix suggerito:**
```bash
# Opzione 1 — match sull'IP dell'istanza
grep -E "${INSTANCE_PUBLIC_IP}.*changed=0" /tmp/lab_second_run.txt

# Opzione 2 — assenza di qualsiasi changed>0 nella seconda run
! grep -E "changed=[1-9][0-9]*" /tmp/lab_second_run.txt
```

---

## Cleanup
**Status:** PASS (parziale)
**Residual artifacts:** Nessuno.

Nessuna risorsa AWS è stata creata (credenziali non valide). La directory locale
`/workspace/ansible-aws-lab` è stata rimossa con `rm -rf` → exit 0.
Il workspace `/workspace` è vuoto. Il volume `/root/.m2` non è stato toccato.

---

## Summary Feedback for Creator

### [BLOCKING] Task 6 — Success criterion idempotency: grep pattern `app_servers.*changed=0` non funziona mai — Task 6

Il PLAY RECAP di Ansible mostra l'IP dell'host (`54.195.10.23`), non il nome del gruppo
inventory (`app_servers`). Il pattern non produrrà mai una corrispondenza: lo studente
completerebbe un deploy idempotente corretto e vedrebbe comunque fallire il check.

**Fix:**
```bash
# Nel success criterion di Task 6, sostituire:
grep -E "app_servers.*changed=0" /tmp/lab_second_run.txt
# con:
grep -E "${INSTANCE_PUBLIC_IP}.*changed=0" /tmp/lab_second_run.txt
# oppure:
! grep -E "changed=[1-9][0-9]*" /tmp/lab_second_run.txt
```

---

### [BLOCKING] Prerequisites — `pip install --user` fallisce su Ubuntu 24.04+/Python 3.11+ con PEP 668 — Setup

Il comando del lab `python3 -m pip install --user "ansible-core>=2.16,<2.22" ...` restituisce:
```
error: externally-managed-environment
This environment is externally managed
```
su qualsiasi Ubuntu ≥ 24.04 (o distro con Python 3.11+ gestito dal sistema). Lo studente
non riesce ad installare Ansible seguendo le istruzioni del lab.

**Fix:** sostituire il comando con una delle seguenti alternative:
```bash
# Opzione A — pipx (raccomandato su Ubuntu 24.04+)
pipx install "ansible-core>=2.16,<2.22"
python3 -m pip install --user --break-system-packages "boto3>=1.34" "botocore>=1.34"

# Opzione B — venv dedicato
python3 -m venv ~/ansible-venv
~/ansible-venv/bin/pip install "ansible-core>=2.16,<2.22" "boto3>=1.34" "botocore>=1.34"
# Poi: export PATH="$HOME/ansible-venv/bin:$PATH"

# Opzione C — workaround rapido (sconsigliato in produzione, accettabile in lab)
python3 -m pip install --user --break-system-packages "ansible-core>=2.16,<2.22" "boto3>=1.34" "botocore>=1.34"
```
Aggiungere anche una nota che avverte che `pip install awscli` installa la v1, non la v2
(già menzionato ma potrebbe essere messo in rilievo nel testo installazione Ansible).

---

### [IMPROVE] Task 4 — Documentazione imprecisa su `--syntax-check` — Task 4

Il lab afferma: *"`--syntax-check` non chiama AWS: valida solo struttura YAML e risoluzione
dei ruoli."* Questo è falso: il plugin inventory `aws_ec2` esegue `ec2:DescribeInstances`
anche durante il syntax-check (~3.4s, AuthFailure visibile senza credenziali). Il success
criterion nasconde il problema con `>/dev/null 2>&1`.

**Fix:** modificare la spiegazione in:
> *"Il syntax-check valida struttura YAML e risoluzione dei ruoli; il plugin inventory
> tenta comunque di contattare AWS ma il check ritorna exit 0 anche in caso di AuthFailure.
> Se vedi warning di credenziali durante il syntax-check, sono attesi e non bloccanti."*

---

### [IMPROVE] Task 5 — `Build application JAR` rieseguito ad ogni run (Maven lento inutilmente) — Task 5

Il task usa `changed_when: git_clone.changed` (reporting corretto) ma non `when: git_clone.changed`
(esecuzione condizionale). Maven viene rieseguito ad ogni run del playbook — anche al secondo run
senza modifiche al codice — impiegando 3-5 minuti inutilmente.

Il lab documenta questa scelta di design nella prosa ("Una condizione tipo `'BUILD SUCCESS' in
mvn_build.stdout` sarebbe sempre vera..."), il che è apprezzabile. Tuttavia la spiegazione
giustifica perché NON usare la condizione sbagliata, non perché omettere `when: git_clone.changed`.

**Fix suggerito** (opzionale — valutare in base all'obiettivo didattico):
```yaml
- name: Build application JAR
  ansible.builtin.command:
    cmd: "{{ (mvnw_stat.stat.exists | bool) | ternary('./mvnw', 'mvn') }} -B -DskipTests package"
    chdir: "{{ springboot_install_dir }}"
  become_user: "{{ springboot_user }}"
  environment:
    HOME: "/home/{{ springboot_user }}"
    MAVEN_OPTS: -Xmx512m
  when: git_clone.changed
  changed_when: true
```
Se si usa `when: git_clone.changed`, il task `Locate produced fat JAR` deve gestire il caso
in cui il JAR esiste già dalla run precedente (aggiungere un `stat` o rimuovere il `Fail if
no JAR` condizionale su `when: git_clone.changed`).

---

### [NICE-TO-HAVE] Task 1 — Aggiungere `--timeout` alla `ansible-galaxy collection install` — Task 1

Galaxy.ansible.com può restituire HTTP 502 transientemente (verificato in questa run).
Aggiungere `--timeout 60` riduce la finestra di errori transitori:
```bash
ansible-galaxy collection install "amazon.aws:>=7.0.0,<12.0.0" --timeout 60
```

---

### [NICE-TO-HAVE] Setup — Placeholder `REPLACE_ME` non evidenziato visivamente — Setup

Il blocco di Setup usa `export AWS_ACCESS_KEY_ID="REPLACE_ME"` senza un callout visibile.
Gli studenti rischiano di procedere senza sostituire i valori.

**Fix:** Aggiungere un callout prominente:
> ⚠️ **Attenzione:** Sostituisci `REPLACE_ME` con le tue credenziali AWS reali prima
> di eseguire qualsiasi comando successivo. Il lab crea risorse AWS reali a pagamento.

---

## Decision

**NEEDS_REVISION**

La versione 3 ha correttamente risolto i bug BLOCKING della v2 (`changed_when` Maven,
doppio daemon_reload, deprecation warning, community.general superflua). La struttura del lab
è solida e il materiale tecnico è ben spiegato. Rimangono però **due bug bloccanti** che
impedirebbero al lab di funzionare correttamente per gli studenti: (1) il success criterion
di Task 6 usa un grep pattern che non matcherà mai l'output reale di Ansible, e (2) la
procedura `pip install --user` fallisce su Ubuntu 24.04+/Python 3.11+ con PEP 668.
Entrambi sono fix di una riga/paragrafo; con quelle correzioni il lab è pronto per la pubblicazione.

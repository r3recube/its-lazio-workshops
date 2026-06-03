# Istruzioni di installazione prerequisiti mancanti

**OS rilevato:** macOS 15.7.4 (darwin-arm64)

Tutti gli altri tool richiesti dal lab sono già presenti e soddisfano i vincoli
di versione. È necessario installare solo le librerie Python `boto3` e `botocore`,
utilizzate dai moduli Ansible della collection `amazon.aws`.

---

## boto3 (include botocore come dipendenza)

`boto3` è l'AWS SDK per Python. `botocore` è la sua libreria di basso livello ed
è installata automaticamente come dipendenza transitiva.

### Diagnosi previa: quale Python usa Ansible?

Prima di installare, verifica quale interprete Python sta usando Ansible, perché
le librerie devono essere installate nello stesso ambiente:

```bash
ansible --version | grep "python version\|python executable"
```

L'output mostrerà qualcosa come:
```
python version = 3.13.12 (...)
python executable = /opt/homebrew/bin/python3.13
```

Prendi nota del path dell'eseguibile Python (`python executable`). Tutti i comandi
`pip` sotto devono usare quel medesimo interprete.

---

### Opzione A — pip nell'ambiente di sistema (percorso più rapido)

Su macOS con Python >= 3.12, pip potrebbe rifiutare l'installazione in ambiente
globale citando PEP 668 ("externally managed environment"). Se succede, usa
`--break-system-packages` oppure passa all'Opzione B.

```bash
# Sostituisci "python3" con il percorso esatto rilevato sopra se necessario
pip3 install boto3
```

Se pip3 restituisce l'errore `externally-managed-environment`:

```bash
pip3 install --break-system-packages boto3
```

**Verifica:**

```bash
python3 -c "import boto3, botocore; print('boto3', boto3.__version__, '/ botocore', botocore.__version__)"
```

---

### Opzione B — virtualenv dedicato (consigliata per ambienti condivisi)

Crea un virtualenv, installa boto3 al suo interno e istruisci Ansible a usare
quell'interprete tramite la variabile `ANSIBLE_PYTHON_INTERPRETER`.

```bash
# Crea il virtualenv (una volta sola)
python3 -m venv ~/.venvs/ansible-aws

# Attiva il virtualenv nella shell corrente
source ~/.venvs/ansible-aws/bin/activate

# Installa boto3 (e botocore come dipendenza)
pip install boto3

# Punto Ansible all'interprete del virtualenv per tutta la sessione
export ANSIBLE_PYTHON_INTERPRETER=~/.venvs/ansible-aws/bin/python
```

> **Nota:** `ANSIBLE_PYTHON_INTERPRETER` deve essere impostata in ogni nuova
> shell, oppure puoi aggiungerla all'`ansible.cfg` del lab:
>
> ```ini
> [defaults]
> interpreter_python = ~/.venvs/ansible-aws/bin/python
> ```

**Verifica:**

```bash
~/.venvs/ansible-aws/bin/python -c \
  "import boto3, botocore; print('boto3', boto3.__version__, '/ botocore', botocore.__version__)"
```

---

### Opzione C — Homebrew (se Ansible stesso è installato via Homebrew)

Se `ansible` è installato tramite Homebrew (`which ansible` riporta
`/opt/homebrew/bin/ansible`), pip3 di Homebrew e il Python di Homebrew sono già
allineati:

```bash
pip3 install boto3
```

Se Homebrew segnala conflitti, usa il pip interno alla formula Python di Homebrew:

```bash
/opt/homebrew/bin/pip3 install boto3
```

**Verifica:**

```bash
/opt/homebrew/bin/python3 -c \
  "import boto3, botocore; print('boto3', boto3.__version__, '/ botocore', botocore.__version__)"
```

---

## Verifica completa post-installazione

Esegui questo blocco dopo aver completato una delle opzioni sopra. Tutti e tre
i comandi devono terminare con exit code 0 e stampare le versioni senza errori.

```bash
# 1. Librerie Python accessibili dall'interprete usato da Ansible
python3 -c "import boto3, botocore; print('boto3', boto3.__version__, '/ botocore', botocore.__version__)"

# 2. Ansible riconosce le collection AWS installate (install se non presenti)
ansible-galaxy collection list amazon.aws 2>/dev/null | grep amazon.aws \
  || ansible-galaxy collection install amazon.aws:>=7.0.0

# 3. Riepilogo versioni di tutti gli altri tool (già OK)
echo "--- tool versions ---"
python3 --version
ansible --version | head -1
aws --version
jq --version
curl --version | head -1
ssh -V
```

Se il comando al punto 1 restituisce ancora `ModuleNotFoundError`, il pip usato
nell'installazione e il Python usato da Ansible non coincidono: ricontrolla il
path con `ansible --version | grep python` e ripeti l'installazione usando
esattamente quell'eseguibile:

```bash
# Esempio con il path esplicito
/path/to/python/usato/da/ansible -m pip install boto3
```

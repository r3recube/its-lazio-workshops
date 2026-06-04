---
lab_id: git-start
version: 1
status: draft
generated_at: 2026-06-04T09:25:33.516366+00:00
based_on_evaluation: null
---

# Lab: Git & GitHub Fundamentals — Real-World Workflow

In questo laboratorio costruirai un repository GitHub completo applicando il
flusso di lavoro che i team di sviluppo usano ogni giorno: branch di feature,
Pull Request, code review enforcement, conflitti reali e CI di base. Tutto
avviene da terminale: nessuna interazione con la web console.

## Learning Objectives

- Creare e configurare un repository GitHub da CLI
- Applicare la strategia trunk-based con feature branch
- Padroneggiare i comandi Git quotidiani: `init`, `clone`, `add`, `commit`,
  `push`, `pull`, `merge`, `rebase`, `log`, `status`, `diff`, `stash`
- Aprire, ispezionare e mergiare Pull Request da CLI
- Configurare la protezione del branch `main` per imporre code review
- Provocare e risolvere un merge conflict reale
- Aggiungere una pipeline CI minimale con GitHub Actions

## Prerequisites

### Tools required (with exact versions)

Verifica che tutti i tool siano installati e con la versione minima richiesta.
Il lab fallisce silenziosamente se anche uno solo manca.

```bash
git --version          # >= 2.39
gh --version           # >= 2.40 (GitHub CLI)
jq --version           # >= 1.6
curl --version | head -1
bash --version | head -1
```

Il GitHub CLI (`gh`) deve essere autenticato con uno scope che permetta la
creazione e cancellazione di repository:

```bash
gh auth status
# Se non autenticato, esegui:
#   gh auth login --scopes "repo,workflow,delete_repo"
# Se già autenticato senza delete_repo:
#   gh auth refresh -h github.com -s delete_repo
```

### Knowledge assumed

- Concetti base di filesystem (cartelle, file, percorsi relativi/assoluti)
- Esecuzione di comandi da terminale bash/zsh
- Editing di file di testo con un editor a tua scelta
- Cosa è un repository remoto (concetto, non operatività)
- Cosa è un Pull Request (concetto, non operatività)

## Setup

Definisci le variabili d'ambiente che useremo per l'intero lab. Il `REPO_NAME`
include un timestamp per evitare collisioni se ripeti il lab più volte.

```bash
# Cartella radice dove verrà clonato il repo
export LAB_ROOT="$HOME/git-labs"

# Nome del repo: prefisso fisso + timestamp epoch
export REPO_NAME="git-start-$(date +%s)"

# Username GitHub corrente, letto direttamente dall'API: niente hardcoding
export GH_USER="$(gh api user --jq .login)"

# Cartella di lavoro completa per il repo clonato
export REPO_DIR="$LAB_ROOT/$REPO_NAME"

mkdir -p "$LAB_ROOT"
echo "LAB_ROOT=$LAB_ROOT"
echo "REPO_NAME=$REPO_NAME"
echo "GH_USER=$GH_USER"
echo "REPO_DIR=$REPO_DIR"
```

### Verifica setup

Questo comando deve uscire con codice 0. Se fallisce, fermati e correggi
prima di proseguire: i task successivi dipendono tutti da queste variabili.

```bash
test -n "$LAB_ROOT" && test -n "$REPO_NAME" && test -n "$GH_USER" \
  && test -d "$LAB_ROOT" \
  && gh auth status >/dev/null 2>&1 \
  && echo "Setup OK"
```

---

## Task 1 — Create the remote repository and clone it locally

**Goal:** Creare un repository pubblico su GitHub via API e ottenerne una copia
locale pronta per i commit.

**Steps:**

Creiamo il repo direttamente con `gh repo create`. Lo facciamo `--public`
perché la protezione branch e Actions richiedono GitHub Pro su repo privati,
mentre sono gratuite su repo pubblici.

```bash
gh repo create "$REPO_NAME" \
  --public \
  --description "Git & GitHub fundamentals lab" \
  --confirm
```

Cloniamo il repo in locale con `git clone`. Tecnicamente `gh repo create`
accetta `--clone` per fare entrambe le operazioni in una volta, ma le
separiamo qui per rendere esplicito ogni passo del workflow.

```bash
cd "$LAB_ROOT"
git clone "https://github.com/$GH_USER/$REPO_NAME.git"
cd "$REPO_DIR"
```

Configuriamo identità Git locale (cioè limitata a questo repo). Usare la
config locale invece che globale evita di sporcare l'identità degli altri
progetti sulla macchina.

```bash
git config user.name "$(gh api user --jq .name // .login)"
git config user.email "$(gh api user --jq '.email // "\(.login)@users.noreply.github.com"')"
git config --local --list | grep '^user\.'
```

Per confronto, `git init` è il comando che useresti se partissi da una
cartella locale vuota e volessi poi connetterla a un remoto. Lo trovi in
qualunque tutorial Git; in questo lab usiamo invece il flusso più comune
in azienda: il repo nasce sul forge, lo cloni in locale.

**Expected outcome:** Una cartella `$REPO_DIR` con dentro `.git/` e il
remoto `origin` configurato verso GitHub. Identità Git impostata a livello
di repo.

**Success criterion:**

```bash
cd "$REPO_DIR" \
  && git remote get-url origin | grep -q "$GH_USER/$REPO_NAME" \
  && git config user.email | grep -q '@' \
  && gh repo view "$GH_USER/$REPO_NAME" --json visibility --jq .visibility | grep -q PUBLIC
```

---

## Task 2 — First commit on main and push to remote

**Goal:** Stabilire la baseline del progetto con un README, un `.gitignore`
e un primo commit firmato spinto sul remoto.

**Steps:**

Creiamo il README. È il file che GitHub mostra in homepage del repo ed è la
prima cosa che chiunque legge: averlo dal commit zero è una buona abitudine.

**File to create:** `README.md`

```markdown
# my-git-lab

Repository per il lab Git & GitHub Fundamentals.

Greetings: Hello world
```

Aggiungiamo un `.gitignore` minimale per escludere artefatti tipici degli
editor. Anche se non abbiamo ancora codice, dichiarare gli esclusi prima
del primo commit evita di tracciare file rumorosi per sbaglio.

**File to create:** `.gitignore`

```gitignore
# Editor & OS
.DS_Store
.idea/
.vscode/
*.swp

# Build output
dist/
build/
*.log
```

Ispezioniamo lo stato dei file non tracciati prima di staging — `git status`
è il comando che dovresti eseguire prima di ogni `git add` per capire cosa
stai per inviare al commit.

```bash
git status
```

Aggiungiamo i due file allo staging area. Usiamo i nomi espliciti invece di
`git add .` per controllare esattamente cosa stagiamo.

```bash
git add README.md .gitignore
```

Ispezioniamo cosa è effettivamente in staging con `git diff --cached`. È il
controllo finale prima di committare: ti mostra il diff tra HEAD e lo
staging area.

```bash
git diff --cached
```

Creiamo il commit. Il messaggio segue la convenzione "tipo: descrizione
imperativa breve" — è uno standard de facto in molti team.

```bash
git commit -m "chore: initial commit with README and gitignore"
```

Pushiamo su `main`. La prima push usa `-u origin main` per impostare
l'upstream tracking: dalle successive push basterà `git push`.

```bash
git push -u origin main
```

Verifichiamo il log del commit appena creato.

```bash
git log --oneline
```

**Expected outcome:** Su GitHub vedi il README renderizzato in homepage del
repo. Localmente `git log` mostra un commit con il messaggio scelto.
`git status` è pulito.

**Success criterion:**

```bash
cd "$REPO_DIR" \
  && [ -f README.md ] && [ -f .gitignore ] \
  && [ -z "$(git status --porcelain)" ] \
  && git log --oneline main | grep -q "initial commit" \
  && git ls-remote --heads origin main | grep -q refs/heads/main
```

---

## Task 3 — Develop a feature on an isolated branch, using stash

**Goal:** Praticare il flusso "feature branch": isolare il lavoro in corso
dal `main`, gestire modifiche non committate con `git stash`, esaminare il
diff prima del commit.

**Steps:**

Prima di creare il branch, simuliamo una situazione realistica: stai per
modificare il README direttamente su `main` quando ti accorgi che dovresti
lavorare su una branch. Apportiamo una piccola modifica al README.

```bash
echo "" >> README.md
echo "TODO: documentare la procedura di setup" >> README.md
git status
```

`git status` mostra `README.md` come modificato ma non in staging. Vogliamo
spostare questo lavoro in corso su un feature branch senza perdere le
modifiche. Usiamo `git stash` per accantonarle temporaneamente.

```bash
git stash push -m "wip: README todo"
git status
git stash list
```

`git status` è ora pulito. Possiamo creare il branch in modo sicuro.
`git checkout -b` crea il branch e ci si sposta sopra in un solo comando.

```bash
git checkout -b feature/hello
```

Recuperiamo le modifiche dallo stash con `git stash pop`. Le modifiche
saranno applicate sul nuovo branch, non più su `main`.

```bash
git stash pop
git status
```

Aggiungiamo il file che è il vero contenuto della feature.

**File to create:** `hello.txt`

```text
Hello from feature/hello.
This file demonstrates the feature-branch workflow.
```

Ispezioniamo il diff completo (non solo staged) prima di committare. `git diff`
senza argomenti mostra le modifiche non ancora in staging.

```bash
git diff
git status
```

Stagiamo entrambi i file modificati. Useremo `git add -A` qui solo per
mostrare l'alternativa "stage tutto"; in produzione preferisci sempre i
path espliciti come abbiamo fatto in Task 2.

```bash
git add -A
git diff --cached
```

Creiamo il commit della feature.

```bash
git commit -m "feat: add hello.txt and README todo"
git log --oneline
```

**Expected outcome:** Sei sul branch `feature/hello`. Il commit appare nel
log. `git status` è pulito. Lo stash è stato svuotato (`git stash list`
non mostra entries).

**Success criterion:**

```bash
cd "$REPO_DIR" \
  && git rev-parse --abbrev-ref HEAD | grep -qx feature/hello \
  && [ -f hello.txt ] \
  && [ -z "$(git status --porcelain)" ] \
  && [ -z "$(git stash list)" ] \
  && git log --oneline feature/hello | grep -q "add hello.txt"
```

---

## Task 4 — Push the feature branch and open a Pull Request

**Goal:** Pubblicare il branch sul remoto e aprire una Pull Request che
diventa l'unità di review e merge.

**Steps:**

Pushiamo il branch impostando l'upstream. Su un branch nuovo serve sempre
`-u` la prima volta.

```bash
git push -u origin feature/hello
```

Apriamo la PR via `gh pr create`. Il flag `--fill` riempie titolo e body
automaticamente dal messaggio del commit, evitando l'editor interattivo.

```bash
gh pr create \
  --base main \
  --head feature/hello \
  --fill
```

Ispezioniamo la PR appena creata. `gh pr view` stampa metadati e descrizione
direttamente in terminale.

```bash
gh pr view
gh pr list
```

Prendiamo nota del numero della PR per riferimenti successivi.

```bash
export PR_HELLO=$(gh pr list --head feature/hello --json number --jq '.[0].number')
echo "PR #$PR_HELLO aperta su feature/hello"
```

**Expected outcome:** Il branch `feature/hello` esiste sul remoto. Una PR
con lo stesso titolo del commit appare in `gh pr list`. La variabile
`$PR_HELLO` contiene un numero intero.

**Success criterion:**

```bash
cd "$REPO_DIR" \
  && git ls-remote --heads origin feature/hello | grep -q refs/heads/feature/hello \
  && [ "$(gh pr list --head feature/hello --json number --jq 'length')" = "1" ]
```

---

## Task 5 — Merge the PR and sync local main

**Goal:** Integrare la feature nel `main` via merge della PR e risincronizzare
la copia locale.

**Steps:**

Mergiamo la PR con strategia `--squash`: tutti i commit del branch diventano
un singolo commit su `main`. È la strategia più usata sui repository
trunk-based perché mantiene la storia di `main` pulita e lineare.
`--delete-branch` rimuove il branch remoto subito dopo il merge.

```bash
gh pr merge "$PR_HELLO" --squash --delete-branch
```

Torniamo su `main` localmente e tiriamo gli aggiornamenti. Senza `git pull`
il tuo `main` locale è fermo al commit pre-merge.

```bash
git checkout main
git pull origin main
git log --oneline
```

Rimuoviamo il branch locale ormai obsoleto. Il branch remoto è già stato
cancellato da `--delete-branch`, ma il locale resta finché non lo elimini
esplicitamente. `-d` rifiuta di cancellare branch non mergiati: è una
safety net contro la perdita di lavoro.

```bash
git branch -d feature/hello
git branch -a
```

**Expected outcome:** `main` contiene il commit squashed con il messaggio
"feat: add hello.txt and README todo (#1)" o simile. Il branch
`feature/hello` non esiste più, né in locale né su origin.

**Success criterion:**

```bash
cd "$REPO_DIR" \
  && git rev-parse --abbrev-ref HEAD | grep -qx main \
  && [ -f hello.txt ] \
  && [ -z "$(git branch --list feature/hello)" ] \
  && [ -z "$(git ls-remote --heads origin feature/hello)" ] \
  && git log --oneline main | grep -qi "hello"
```

---

## Task 6 — Add a CI workflow with GitHub Actions

**Goal:** Aggiungere una pipeline che si esegue automaticamente su ogni PR
verso `main`, validando un set minimo di invarianti del repo.

**Steps:**

Creiamo un branch dedicato. Anche un cambio di sola CI passa dalla PR:
nessun commit diretto su `main` se vogliamo abituarci al flusso giusto.

```bash
git checkout -b chore/ci
```

Creiamo la directory standard dei workflow di GitHub Actions. Actions cerca
i workflow esattamente in `.github/workflows/` con estensione `.yml` o
`.yaml`: altre posizioni sono ignorate.

```bash
mkdir -p .github/workflows
```

Definiamo il workflow. Il trigger `pull_request` verso `main` significa che
il job parte ogni volta che viene aperta o aggiornata una PR su `main`.

**File to create:** `.github/workflows/ci.yml`

```yaml
name: ci
on:
  pull_request:
    branches: [main]
jobs:
  validate:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: README must exist
        run: test -f README.md

      - name: No debug or temp files committed
        run: |
          if find . -type f \( -name "*.debug" -o -name "*.tmp" -o -name "*.bak" \) \
               -not -path "./.git/*" | grep -q .; then
            echo "Trovati file di debug/temp committati"
            exit 1
          fi
          echo "Filesystem pulito"

      - name: README must contain Greetings line
        run: grep -q "^Greetings:" README.md
```

Committiamo, pushiamo, apriamo la PR e la mergiamo. Per la PR usiamo flag
non interattivi.

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add pull_request validation workflow"
git push -u origin chore/ci

gh pr create --base main --head chore/ci \
  --title "ci: add validation workflow" \
  --body "Aggiunge il primo workflow CI che gira su tutte le PR verso main."

export PR_CI=$(gh pr list --head chore/ci --json number --jq '.[0].number')
gh pr merge "$PR_CI" --squash --delete-branch

git checkout main
git pull origin main
git branch -d chore/ci
```

Verifichiamo che almeno una run del workflow sia stata triggerata. Le run
appaiono pochi secondi dopo l'apertura della PR; aspettiamo qualche istante
per dare a GitHub il tempo di registrarla.

```bash
sleep 8
gh run list --workflow ci.yml
```

**Expected outcome:** Il file `.github/workflows/ci.yml` è su `main`.
`gh run list` mostra almeno una esecuzione del workflow `ci` (può essere
in stato `completed`, `in_progress` o `queued`).

**Success criterion:**

```bash
cd "$REPO_DIR" \
  && [ -f .github/workflows/ci.yml ] \
  && git log --oneline main -- .github/workflows/ci.yml | grep -q . \
  && [ "$(gh run list --workflow ci.yml --json status --jq 'length')" -ge 1 ]
```

---

## Task 7 — Protect the main branch

**Goal:** Vietare i push diretti su `main` e imporre che ogni modifica passi
da una Pull Request.

**Steps:**

Applichiamo le branch protection rules via API REST. Le impostazioni che
configuriamo:

- `enforce_admins: true` → la regola vale anche per chi è admin del repo
  (cioè tu, in questo lab). Senza questo, gli admin bypassano la protezione
  e la lezione perderebbe senso.
- `required_pull_request_reviews.required_approving_review_count: 0` → la
  PR è obbligatoria, ma non servono approvazioni esterne. In un team reale
  metteresti almeno 1; qui sei solo, quindi 0.
- `allow_force_pushes: false` e `allow_deletions: false` → impediscono
  rispettivamente `git push --force` e la cancellazione di `main`.

```bash
gh api --method PUT \
  -H "Accept: application/vnd.github+json" \
  "repos/$GH_USER/$REPO_NAME/branches/main/protection" \
  --input - <<'JSON'
{
  "required_status_checks": null,
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "required_approving_review_count": 0,
    "dismiss_stale_reviews": false,
    "require_code_owner_reviews": false
  },
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
JSON
```

Verifichiamo che la regola sia attiva. La GET sull'endpoint protection ritorna
oggetto JSON con `enforce_admins.enabled = true`.

```bash
gh api "repos/$GH_USER/$REPO_NAME/branches/main/protection" \
  --jq '{enforce_admins: .enforce_admins.enabled, reviews_required: .required_pull_request_reviews.required_approving_review_count}'
```

Proviamo a violare la regola: un commit diretto su `main` seguito da push
deve essere rifiutato dal remoto.

```bash
echo "tentativo di bypass" >> README.md
git add README.md
git commit -m "test: direct push to protected main"
git push origin main || echo "PUSH RIFIUTATO COME ATTESO"

# Annulliamo il commit locale fallito per ripulire la situazione
git reset --hard origin/main
git status
```

**Expected outcome:** Il `git push` esce con errore (`GH006: Protected
branch update failed`). Dopo il reset, `git status` è pulito e
`git log` non contiene il commit di bypass.

**Success criterion:**

```bash
cd "$REPO_DIR" \
  && gh api "repos/$GH_USER/$REPO_NAME/branches/main/protection" \
       --jq '.enforce_admins.enabled' | grep -qx true \
  && gh api "repos/$GH_USER/$REPO_NAME/branches/main/protection" \
       --jq '.required_pull_request_reviews' | grep -q "required_approving_review_count" \
  && [ -z "$(git status --porcelain)" ]
```

---

## Task 8 — Provoke and resolve a merge conflict

**Goal:** Creare due branch che modificano la stessa riga, mergiare il primo,
provocare il conflitto sul secondo, risolverlo con `git rebase` e completare
il merge della PR.

**Steps:**

Creiamo il primo branch concorrente. Modifica la riga "Greetings:" del README.

```bash
git checkout -b feature/greeting-it
```

Riscrivere una riga specifica in modo deterministico via shell è fragile;
usiamo invece una piccola riscrittura con `awk` che sostituisce la riga che
inizia con `Greetings:` mantenendo il resto intatto.

```bash
awk '/^Greetings:/ {print "Greetings: Saluti dall'\''Italia"; next} {print}' README.md > README.md.tmp
mv README.md.tmp README.md
git diff
```

Committiamo e pushiamo il branch, ma NON mergiamo ancora la PR.

```bash
git add README.md
git commit -m "feat: greeting in italian"
git push -u origin feature/greeting-it

gh pr create --base main --head feature/greeting-it \
  --title "feat: italian greeting" \
  --body "Cambia greeting in italiano."

export PR_IT=$(gh pr list --head feature/greeting-it --json number --jq '.[0].number')
```

Creiamo il secondo branch partendo dallo stesso commit di `main` (non da
`feature/greeting-it`): questo è il punto chiave per produrre il conflitto.

```bash
git checkout main
git checkout -b feature/greeting-fr
```

Modifichiamo la stessa riga, ma con un valore diverso.

```bash
awk '/^Greetings:/ {print "Greetings: Salutations de France"; next} {print}' README.md > README.md.tmp
mv README.md.tmp README.md
git diff
git add README.md
git commit -m "feat: greeting in french"
git push -u origin feature/greeting-fr

gh pr create --base main --head feature/greeting-fr \
  --title "feat: french greeting" \
  --body "Cambia greeting in francese."

export PR_FR=$(gh pr list --head feature/greeting-fr --json number --jq '.[0].number')
```

A questo punto le due PR esistono e nessuna è ancora mergiata. Mergiamo
quella italiana per prima.

```bash
gh pr merge "$PR_IT" --squash --delete-branch
git checkout main
git pull origin main
git log --oneline -3
```

Tentiamo di mergiare la PR francese. Fallisce: il `main` ha cambiato la
stessa riga e GitHub rileva il conflitto.

```bash
gh pr merge "$PR_FR" --squash --delete-branch \
  || echo "MERGE RIFIUTATO: conflitto da risolvere"
```

Andiamo sul branch francese e rebasiamo sopra l'attuale `main`. `git rebase`
ricostruisce i nostri commit come se fossero stati fatti partendo da
`origin/main` aggiornato.

```bash
git fetch origin
git checkout feature/greeting-fr
git rebase origin/main
```

Il rebase si interrompe sul conflitto. `git status` mostra il file in
stato "both modified". Apri `README.md` con un editor e troverai i marker:

```
<<<<<<< HEAD
Greetings: Saluti dall'Italia
=======
Greetings: Salutations de France
>>>>>>> feat: greeting in french
```

Risolviamo mantenendo entrambi i saluti su una sola riga, separati da `/`.
Lo facciamo con uno script per essere deterministici durante il test del
lab; in scenari reali useresti l'editor.

```bash
python3 - <<'PY'
import re, pathlib
p = pathlib.Path("README.md")
text = p.read_text()
resolved = re.sub(
    r"<<<<<<<[^\n]*\nGreetings: Saluti dall'Italia\n=======\nGreetings: Salutations de France\n>>>>>>>[^\n]*\n",
    "Greetings: Saluti dall'Italia / Salutations de France\n",
    text,
)
p.write_text(resolved)
PY

git diff
grep "^Greetings:" README.md
```

Marchiamo il file come risolto e proseguiamo il rebase.

```bash
git add README.md
git rebase --continue
```

Pushiamo il branch francese ricostruito. Il rebase ha riscritto la storia,
quindi serve `--force-with-lease` (versione safe di `--force`: rifiuta il
push se nel frattempo qualcuno ha aggiornato il remoto).

```bash
git push --force-with-lease origin feature/greeting-fr
```

Ora la PR francese è mergeable senza conflitti.

```bash
gh pr merge "$PR_FR" --squash --delete-branch
git checkout main
git pull origin main
git branch -d feature/greeting-it 2>/dev/null || true
git branch -d feature/greeting-fr
git log --oneline -5
```

**Expected outcome:** Il `main` finale contiene entrambi i saluti su una
riga sola: `Greetings: Saluti dall'Italia / Salutations de France`.
Entrambe le PR risultano `MERGED` in `gh pr list --state merged`.

**Success criterion:**

```bash
cd "$REPO_DIR" \
  && git rev-parse --abbrev-ref HEAD | grep -qx main \
  && grep -q "Saluti dall'Italia" README.md \
  && grep -q "Salutations de France" README.md \
  && [ "$(gh pr list --state merged --json number --jq 'length')" -ge 3 ]
```

---

## Cleanup

Rimuove l'intero repository da GitHub e la cartella locale. Esegui questo
blocco solo quando hai finito: non è reversibile.

```bash
# Rimuove le branch protection (necessario su alcune versioni di gh prima della delete)
gh api --method DELETE \
  -H "Accept: application/vnd.github+json" \
  "repos/$GH_USER/$REPO_NAME/branches/main/protection" >/dev/null 2>&1 || true

# Cancella il repository remoto
gh repo delete "$GH_USER/$REPO_NAME" --yes

# Rimuove la copia locale
cd "$HOME"
rm -rf "$REPO_DIR"

# Pulisce le variabili d'ambiente del lab
unset LAB_ROOT REPO_NAME GH_USER REPO_DIR PR_HELLO PR_CI PR_IT PR_FR

echo "Cleanup completato"
```

Verifica finale che la cancellazione sia andata a buon fine:

```bash
gh repo view "$GH_USER/$REPO_NAME" 2>&1 | grep -qi "could not resolve" && echo "Repo cancellato OK"
```

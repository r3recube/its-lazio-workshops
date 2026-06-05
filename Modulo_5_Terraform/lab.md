---
lab_id: terraform-aws
version: 1
status: draft
generated_at: 2026-06-04T15:41:26.749452+00:00
based_on_evaluation: null
---

# Terraform on AWS: from first apply to a secure multi-tier architecture

Lab progressivo in quattro task. Si parte da una singola istanza EC2 dichiarata
in HCL, si integra Ansible per la configurazione OS, si costruisce uno stack
multi-tier (ALB + 2× EC2 + RDS MySQL) e infine si esegue uno scan di sicurezza
con tfsec correggendo i finding HIGH/CRITICAL.

Ogni task è isolato in una propria cartella (`task1/`, `task2/`, `task3/`) per
permettere di interrompere a fine task e ripartire pulito.

## Learning Objectives

- Installare e configurare la CLI Terraform e il provider AWS
- Scrivere risorse HCL: `aws_instance`, `aws_lb`, `aws_db_instance`, security group
- Eseguire il ciclo `terraform init → plan → apply → destroy`
- Comporre un'architettura multi-AZ e multi-tier (EC2 + ALB + RDS MySQL)
- Integrare Ansible con gli output di Terraform per configurare le istanze
- Eseguire lo scan di sicurezza del codice Terraform con tfsec
- Correggere finding reali di tfsec, capendo i controlli AWS sottostanti

## Prerequisites

### Tools required (with exact versions)

I comandi `--version` qui sotto sono il check di presenza. Le versioni mostrate
sono le minime testate.

```bash
terraform version              # >= 1.7.0
aws --version                  # >= 2.15
ansible --version              # >= 2.15
python3 --version              # >= 3.9
jq --version                   # >= 1.6
curl --version                 # any
ssh -V                         # OpenSSH any
tfsec --version                # >= 1.28 (installato in Task 4, non serve ora)
```

Pacchetti Python richiesti per Ansible:

```bash
python3 -c "import boto3, botocore" && echo "boto3 OK"
```

Se manca: `pip install --user ansible boto3 botocore`.

### Knowledge assumed

- Concetti di base AWS: regione, AZ, EC2, VPC, subnet, security group
- Cosa è una coppia di chiavi SSH e come la si usa con EC2
- Modello client/server HTTP e nozione di load balancer
- DNS pubblico (FQDN) e risoluzione locale
- Concetto di Infrastructure as Code: descrivere lo stato target invece di
  comandi imperativi
- Cosa è un'istanza RDS (servizio managed di DB relazionale)

Non sono richieste competenze HCL pregresse, né esperienza con Terraform o
Ansible.

## Setup

Tutte le variabili d'ambiente impostate qui sono usate dai task successivi.
Apri un singolo terminale e mantienilo aperto per tutta la durata del lab; se
chiudi il terminale dovrai riesportare queste variabili.

```bash
# Cartella di lavoro del lab. Tutti i path nel lab sono relativi a questa.
export LAB_DIR="$HOME/recube-labs/terraform-aws"
mkdir -p "$LAB_DIR" && cd "$LAB_DIR"

# Regione di lavoro fissata dal brief. Cambiala SOLO se il brief è stato variato.
export AWS_REGION="eu-west-1"
export AWS_DEFAULT_REGION="$AWS_REGION"
```

Recupera il tuo IP pubblico, che servirà come sorgente autorizzata per SSH e
per gli accessi amministrativi:

```bash
# Il check-IP di AWS è stabile e restituisce solo l'IP, senza newline aggiuntivi
export MY_PUBLIC_IP="$(curl -sS https://checkip.amazonaws.com | tr -d '\n')"
echo "Public IP detected: $MY_PUBLIC_IP"
```

Imposta nome della coppia di chiavi EC2 esistente nella regione e path al file
PEM corrispondente. Sostituisci i due valori con quelli del tuo account.

```bash
# Nome della Key Pair come appare in AWS (es. "my-lab-key")
export KEY_PAIR_NAME="REPLACE_WITH_YOUR_KEYPAIR_NAME"

# Path locale alla chiave privata corrispondente; deve avere permessi 400
export SSH_KEY="$HOME/.ssh/${KEY_PAIR_NAME}.pem"
chmod 400 "$SSH_KEY" 2>/dev/null || true
```

Genera una password per il database RDS che verrà creato in Task 3. La salviamo
in una variabile d'ambiente con prefisso `TF_VAR_` perché Terraform mappa
automaticamente `TF_VAR_<nome>` su `var.<nome>`, evitando di scrivere la
password in chiaro in file `.tf`.

```bash
# 20 caratteri alfanumerici sufficienti per il vincolo RDS (>= 8 char)
export DB_PASSWORD="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 20)"
export TF_VAR_db_password="$DB_PASSWORD"
export TF_VAR_key_pair_name="$KEY_PAIR_NAME"
export TF_VAR_admin_cidr="${MY_PUBLIC_IP}/32"
export TF_VAR_aws_region="$AWS_REGION"
```

Disabilitiamo l'host key checking di Ansible: le istanze vengono distrutte e
ricreate continuamente nel lab, le impronte SSH cambiano e bloccherebbero la
connessione automatica.

```bash
export ANSIBLE_HOST_KEY_CHECKING=False
```

### Verifica setup

Un singolo comando che valida tutti i requisiti host. Esce con 0 se tutto è
pronto, stampando una riga "Setup OK".

```bash
terraform version >/dev/null 2>&1 \
  && aws --version >/dev/null 2>&1 \
  && ansible --version >/dev/null 2>&1 \
  && python3 -c "import boto3, botocore" 2>/dev/null \
  && jq --version >/dev/null 2>&1 \
  && aws sts get-caller-identity >/dev/null 2>&1 \
  && aws ec2 describe-key-pairs --key-names "$KEY_PAIR_NAME" --region "$AWS_REGION" >/dev/null 2>&1 \
  && [ -r "$SSH_KEY" ] \
  && [ -d "$LAB_DIR" ] \
  && echo "Setup OK"
```

Se non vedi `Setup OK`, identifica quale check ha fallito eseguendo i comandi
singolarmente prima di proseguire.

## Task 1 — Terraform basics: una singola EC2

**Goal:** Scrivere il primo file HCL, eseguire il ciclo completo
`init → plan → apply → destroy` su una singola istanza EC2, e capire cosa
ciascun comando fa nello state Terraform.

**Steps:**

Creiamo una sottocartella isolata per questo task così che lo state Terraform
non si mescoli con i task successivi.

```bash
mkdir -p "$LAB_DIR/task1" && cd "$LAB_DIR/task1"
```

Scriviamo il primo file HCL. Contiene il blocco `terraform` (che vincola la
versione del CLI e del provider per garantire la riproducibilità), il blocco
`provider` (che configura la regione AWS), un data source per scoprire l'AMI
Amazon Linux 2023 più recente — invece di hardcodare un ID che cambia ogni
mese — e una sola risorsa `aws_instance`.

**File to create:** `$LAB_DIR/task1/main.tf`

```hcl
terraform {
  required_version = ">= 1.7"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = "eu-west-1"

  default_tags {
    tags = {
      lab = "terraform-aws"
    }
  }
}

# Cerca l'AMI Amazon Linux 2023 più recente, x86_64.
# L'ID AMI cambia ad ogni release: hardcodarlo renderebbe il codice fragile.
data "aws_ami" "amazon_linux_2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }

  filter {
    name   = "architecture"
    values = ["x86_64"]
  }
}

resource "aws_instance" "lab" {
  ami           = data.aws_ami.amazon_linux_2023.id
  instance_type = "t3.micro"

  tags = {
    Name = "terraform-lab-task1"
  }
}

output "instance_id" {
  value       = aws_instance.lab.id
  description = "ID dell'istanza EC2 creata da Terraform"
}
```

Inizializziamo il backend e scarichiamo il provider. `terraform init` scrive in
`.terraform/` i binari del provider e crea (vuoto) il file di lock
`.terraform.lock.hcl` che fissa le versioni dei provider.

```bash
terraform init
```

Generiamo il plan, ovvero la diff tra lo state desiderato (`main.tf`) e lo
state attuale (vuoto). Il plan ci permette di rivedere cosa verrà creato prima
di applicare.

```bash
terraform plan
```

Applichiamo il plan. `-auto-approve` salta la conferma interattiva: utile in
contesti automatici, accettabile in lab. In produzione lo si evita.

```bash
terraform apply -auto-approve
```

Estraiamo l'output `instance_id` e lo salviamo su file per poterlo verificare
anche dopo il `destroy`. Il file su `/tmp` ci serve come prova "post-mortem"
che il task è stato eseguito correttamente.

```bash
terraform output -raw instance_id | tee /tmp/task1_instance_id.txt
echo
```

Distruggiamo le risorse. Questo è parte integrante del Task 1: vogliamo
osservare che Terraform sa anche smontare ciò che ha creato, leggendo lo state.

```bash
terraform destroy -auto-approve
```

**Expected outcome:** `apply` ha creato un'istanza `t3.micro` in eu-west-1
con tag `lab=terraform-aws` e `Name=terraform-lab-task1`. `terraform output`
ha restituito un ID nella forma `i-0123456789abcdef0`. `destroy` ha rimosso
l'istanza e lo state Terraform è ora vuoto (`terraform.tfstate` contiene solo
metadati di versione).

**Success criterion:**

```bash
grep -Eq '^i-[0-9a-f]{17}$' /tmp/task1_instance_id.txt
```

## Task 2 — Integrazione Terraform + Ansible

**Goal:** Estendere Task 1 aggiungendo un security group SSH dedicato e una
chiave di accesso, lasciare l'istanza viva dopo `apply`, e usare Ansible per
configurare l'hostname leggendo l'IP pubblico direttamente dagli output di
Terraform.

**Steps:**

Nuova cartella isolata per Task 2.

```bash
mkdir -p "$LAB_DIR/task2" && cd "$LAB_DIR/task2"
```

Scriviamo il main.tf estendendo Task 1: aggiungiamo le variabili
`key_pair_name` e `admin_cidr` (già popolate via `TF_VAR_*` nel Setup), un
security group che apre la porta 22 solo dal nostro IP, e gli output
`instance_id` e `instance_public_ip` che useremo per costruire l'inventory
Ansible.

**File to create:** `$LAB_DIR/task2/main.tf`

```hcl
terraform {
  required_version = ">= 1.7"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

variable "aws_region" {
  type        = string
  default     = "eu-west-1"
  description = "AWS region"
}

variable "key_pair_name" {
  type        = string
  description = "Nome della Key Pair EC2 esistente nella regione"
}

variable "admin_cidr" {
  type        = string
  description = "CIDR autorizzato per SSH (es. 203.0.113.5/32)"
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      lab = "terraform-aws"
    }
  }
}

data "aws_ami" "amazon_linux_2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }

  filter {
    name   = "architecture"
    values = ["x86_64"]
  }
}

# SG dedicato: ingress 22 dal solo IP dell'operatore, egress completo.
# Tenere SG e istanza in risorse separate permette di riusarli e di leggerli
# meglio nel plan.
resource "aws_security_group" "ssh" {
  name        = "terraform-lab-task2-ssh"
  description = "Allow SSH from operator IP"

  ingress {
    description = "SSH from operator"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.admin_cidr]
  }

  egress {
    description = "All egress"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "terraform-lab-task2-ssh"
  }
}

resource "aws_instance" "lab" {
  ami                    = data.aws_ami.amazon_linux_2023.id
  instance_type          = "t3.micro"
  key_name               = var.key_pair_name
  vpc_security_group_ids = [aws_security_group.ssh.id]

  tags = {
    Name = "terraform-lab-task2"
  }
}

output "instance_id" {
  value = aws_instance.lab.id
}

output "instance_public_ip" {
  value       = aws_instance.lab.public_ip
  description = "IP pubblico dell'istanza, usato da Ansible come host"
}
```

Init + apply. Le variabili sono già esportate come `TF_VAR_*` nel Setup,
quindi non servono flag aggiuntivi.

```bash
terraform init
terraform apply -auto-approve
```

Scriviamo l'inventory Ansible costruito dinamicamente dall'output di Terraform.
Il valore `$SSH_KEY` viene espanso dalla shell al momento della scrittura del
file, quindi l'inventory contiene un path concreto.

```bash
cat > inventory.ini <<EOF
[lab]
$(terraform output -raw instance_public_ip) ansible_user=ec2-user ansible_ssh_private_key_file=${SSH_KEY}

[lab:vars]
ansible_ssh_common_args=-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null
EOF
cat inventory.ini
```

Scriviamo un playbook minimale che imposta l'hostname. Usiamo `wait_for_connection`
all'inizio perché l'istanza, appena terminata l'apply, potrebbe ancora non
avere SSH pronto (cloud-init non completato).

**File to create:** `$LAB_DIR/task2/configure.yml`

```yaml
---
- name: Configure lab node hostname
  hosts: lab
  become: true
  gather_facts: false
  tasks:
    - name: Wait for SSH to become available
      ansible.builtin.wait_for_connection:
        timeout: 300

    - name: Set system hostname
      ansible.builtin.hostname:
        name: terraform-lab-node

    - name: Ensure hostname survives reboot (/etc/hostname)
      ansible.builtin.copy:
        content: "terraform-lab-node\n"
        dest: /etc/hostname
        owner: root
        group: root
        mode: '0644'
```

Eseguiamo il playbook.

```bash
ansible-playbook -i inventory.ini configure.yml
```

**Expected outcome:** Terraform ha creato l'istanza e il SG. Ansible si è
connesso via SSH usando l'IP pubblico esposto come output Terraform e ha
configurato l'hostname. Un `ssh` manuale all'istanza restituisce
`terraform-lab-node` come hostname.

**Success criterion:**

```bash
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i "$SSH_KEY" \
  ec2-user@$(cd "$LAB_DIR/task2" && terraform output -raw instance_public_ip) \
  hostname 2>/dev/null | grep -Fxq terraform-lab-node
```

Distruggiamo le risorse di Task 2 prima di passare a Task 3, così non
accumuliamo costi inutili:

```bash
cd "$LAB_DIR/task2" && terraform destroy -auto-approve
```

## Task 3 — Architettura multi-tier: 2× EC2 + ALB + RDS + PHP

**Goal:** Costruire uno stack realistico a tre tier (web → app → DB) usando
solo Terraform per l'infrastruttura e Ansible per la configurazione applicativa.
Il deliverable è un endpoint ALB che risponde con una pagina HTML elencando
auto lette via PHP da MySQL su RDS.

**Steps:**

Nuova cartella, con struttura più strutturata: separiamo provider/variabili/
risorse/output in file distinti perché il volume di codice cresce.

```bash
mkdir -p "$LAB_DIR/task3/templates" && cd "$LAB_DIR/task3"
```

Il file `providers.tf` fissa versioni e provider. Aggiungiamo qui anche
`random` perché ci servirà per generare un suffisso univoco per il bucket S3
dei log ALB in Task 4.

**File to create:** `$LAB_DIR/task3/providers.tf`

```hcl
terraform {
  required_version = ">= 1.7"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.5"
    }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      lab = "terraform-aws"
    }
  }
}
```

Variabili: regione, key pair, CIDR admin, credenziali DB. La password è
`sensitive = true` così Terraform non la stampa nei plan/apply log.

**File to create:** `$LAB_DIR/task3/variables.tf`

```hcl
variable "aws_region" {
  type    = string
  default = "eu-west-1"
}

variable "key_pair_name" {
  type        = string
  description = "Nome della Key Pair EC2 esistente"
}

variable "admin_cidr" {
  type        = string
  description = "CIDR autorizzato per SSH amministrativo"
}

variable "db_username" {
  type        = string
  default     = "labadmin"
  description = "Username master di RDS"
}

variable "db_password" {
  type        = string
  sensitive   = true
  description = "Password master di RDS — passata via TF_VAR_db_password"
}
```

Risorse principali. Il file è lungo ma volutamente lineare: rete, security
group, EC2, ALB, RDS, nell'ordine di dipendenza decrescente del rischio
(rete è la base, RDS è l'ultima e impiega più tempo).

**File to create:** `$LAB_DIR/task3/main.tf`

```hcl
# AMI lookup riutilizzato per entrambe le EC2.
data "aws_ami" "amazon_linux_2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }

  filter {
    name   = "architecture"
    values = ["x86_64"]
  }
}

# ----------------------------------------------------------------------------
# Networking: VPC dedicata + 2 subnet pubbliche in AZ diverse + IGW + RT
# ----------------------------------------------------------------------------

# VPC dedicata: separare la rete del lab dalla default VPC evita conflitti e
# rende il destroy pulito.
resource "aws_vpc" "lab" {
  cidr_block           = "10.20.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "terraform-lab-vpc"
  }
}

resource "aws_internet_gateway" "lab" {
  vpc_id = aws_vpc.lab.id

  tags = {
    Name = "terraform-lab-igw"
  }
}

# Le due subnet pubbliche. map_public_ip_on_launch = true ci risparmia
# l'allocazione manuale di EIP per le EC2 di lab.
resource "aws_subnet" "public_a" {
  vpc_id                  = aws_vpc.lab.id
  cidr_block              = "10.20.1.0/24"
  availability_zone       = "${var.aws_region}a"
  map_public_ip_on_launch = true

  tags = {
    Name = "terraform-lab-public-a"
  }
}

resource "aws_subnet" "public_b" {
  vpc_id                  = aws_vpc.lab.id
  cidr_block              = "10.20.2.0/24"
  availability_zone       = "${var.aws_region}b"
  map_public_ip_on_launch = true

  tags = {
    Name = "terraform-lab-public-b"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.lab.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.lab.id
  }

  tags = {
    Name = "terraform-lab-public-rt"
  }
}

resource "aws_route_table_association" "public_a" {
  subnet_id      = aws_subnet.public_a.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "public_b" {
  subnet_id      = aws_subnet.public_b.id
  route_table_id = aws_route_table.public.id
}

# RDS richiede un DB Subnet Group con subnet in almeno 2 AZ. Riusiamo le due
# pubbliche: RDS ha publicly_accessible = false e SG che permette solo EC2,
# quindi non è raggiungibile da internet anche se le subnet sono "public".
resource "aws_db_subnet_group" "lab" {
  name       = "terraform-lab-db-subnets"
  subnet_ids = [aws_subnet.public_a.id, aws_subnet.public_b.id]

  tags = {
    Name = "terraform-lab-db-subnets"
  }
}

# ----------------------------------------------------------------------------
# Security Groups: ALB → EC2 → RDS, con reference per SG anziché CIDR.
# ----------------------------------------------------------------------------

# ALB accetta HTTP 80 da chiunque, perché l'ALB è il punto di ingresso pubblico
# dell'applicazione.
resource "aws_security_group" "alb" {
  name        = "terraform-lab-alb"
  description = "ALB ingress 80 from internet"
  vpc_id      = aws_vpc.lab.id

  ingress {
    description = "HTTP from anywhere"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "All egress"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "terraform-lab-alb-sg"
  }
}

# EC2: HTTP 80 SOLO dal SG dell'ALB (niente CIDR), SSH SOLO dall'operatore.
# Referenziare un SG via security_groups invece di cidr_blocks è la pratica
# corretta: se l'ALB scala/sposta sottoreti, la regola continua a funzionare.
resource "aws_security_group" "ec2" {
  name        = "terraform-lab-ec2"
  description = "EC2 ingress: 80 from ALB SG, 22 from operator"
  vpc_id      = aws_vpc.lab.id

  ingress {
    description     = "HTTP from ALB"
    from_port       = 80
    to_port         = 80
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  ingress {
    description = "SSH from operator"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.admin_cidr]
  }

  egress {
    description = "All egress"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "terraform-lab-ec2-sg"
  }
}

# RDS: 3306 SOLO dal SG delle EC2. Niente accessi diretti, niente CIDR pubblici.
resource "aws_security_group" "rds" {
  name        = "terraform-lab-rds"
  description = "RDS ingress 3306 from EC2 SG only"
  vpc_id      = aws_vpc.lab.id

  ingress {
    description     = "MySQL from EC2 SG"
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [aws_security_group.ec2.id]
  }

  egress {
    description = "All egress"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "terraform-lab-rds-sg"
  }
}

# ----------------------------------------------------------------------------
# Compute: 2 EC2, una per AZ, dietro l'ALB.
# ----------------------------------------------------------------------------

resource "aws_instance" "web_a" {
  ami                         = data.aws_ami.amazon_linux_2023.id
  instance_type               = "t3.micro"
  subnet_id                   = aws_subnet.public_a.id
  vpc_security_group_ids      = [aws_security_group.ec2.id]
  key_name                    = var.key_pair_name
  associate_public_ip_address = true

  tags = {
    Name = "terraform-lab-web-a"
  }
}

resource "aws_instance" "web_b" {
  ami                         = data.aws_ami.amazon_linux_2023.id
  instance_type               = "t3.micro"
  subnet_id                   = aws_subnet.public_b.id
  vpc_security_group_ids      = [aws_security_group.ec2.id]
  key_name                    = var.key_pair_name
  associate_public_ip_address = true

  tags = {
    Name = "terraform-lab-web-b"
  }
}

# ----------------------------------------------------------------------------
# ALB + target group + listener HTTP.
# ----------------------------------------------------------------------------

resource "aws_lb" "lab" {
  name               = "terraform-lab-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = [aws_subnet.public_a.id, aws_subnet.public_b.id]

  tags = {
    Name = "terraform-lab-alb"
  }
}

resource "aws_lb_target_group" "web" {
  name     = "terraform-lab-tg"
  port     = 80
  protocol = "HTTP"
  vpc_id   = aws_vpc.lab.id

  health_check {
    path                = "/test.php"
    matcher             = "200"
    interval            = 30
    timeout             = 10
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = {
    Name = "terraform-lab-tg"
  }
}

resource "aws_lb_target_group_attachment" "web_a" {
  target_group_arn = aws_lb_target_group.web.arn
  target_id        = aws_instance.web_a.id
  port             = 80
}

resource "aws_lb_target_group_attachment" "web_b" {
  target_group_arn = aws_lb_target_group.web.arn
  target_id        = aws_instance.web_b.id
  port             = 80
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.lab.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.web.arn
  }
}

# ----------------------------------------------------------------------------
# RDS MySQL 8.0, instanza singola, accesso privato.
# skip_final_snapshot = true perché vogliamo destroy puliti senza snapshot
# residui che pesano sul billing.
# ----------------------------------------------------------------------------

resource "aws_db_instance" "lab" {
  identifier             = "terraform-lab-db"
  engine                 = "mysql"
  engine_version         = "8.0"
  instance_class         = "db.t3.micro"
  allocated_storage      = 20
  db_name                = "labdb"
  username               = var.db_username
  password               = var.db_password
  publicly_accessible    = false
  multi_az               = false
  db_subnet_group_name   = aws_db_subnet_group.lab.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  skip_final_snapshot    = true

  tags = {
    Name = "terraform-lab-db"
  }
}
```

Output, raggruppati in un file dedicato.

**File to create:** `$LAB_DIR/task3/outputs.tf`

```hcl
output "alb_dns_name" {
  value       = aws_lb.lab.dns_name
  description = "FQDN dell'Application Load Balancer"
}

output "rds_endpoint" {
  value       = aws_db_instance.lab.endpoint
  description = "Endpoint host:port di RDS"
}

output "rds_address" {
  value       = aws_db_instance.lab.address
  description = "Solo hostname RDS (senza porta), usato da Ansible/PHP"
}

output "ec2_public_ips" {
  value       = [aws_instance.web_a.public_ip, aws_instance.web_b.public_ip]
  description = "IP pubblici delle due EC2"
}

output "target_group_arn" {
  value       = aws_lb_target_group.web.arn
  description = "ARN del target group, utile per gli health check via AWS CLI"
}
```

Init + apply. Questo apply impiega ~7-10 minuti, dominati dalla creazione di
RDS. Mentre attende, Terraform mostra il progresso di ciascuna risorsa.

```bash
terraform init
terraform apply -auto-approve
```

Scriviamo l'inventory Ansible dinamicamente dagli output. `terraform output -json`
restituisce JSON parsabile da jq.

```bash
cat > inventory.ini <<EOF
[web]
$(terraform output -json ec2_public_ips | jq -r '.[]')

[web:vars]
ansible_user=ec2-user
ansible_ssh_private_key_file=${SSH_KEY}
ansible_python_interpreter=/usr/bin/python3
ansible_ssh_common_args=-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null
EOF
cat inventory.ini
```

Schema + seed dati MySQL. È un file SQL standalone che Ansible copierà su una
delle EC2 e darà in pasto a `mysql` per popolare il DB. Drop + create assicura
idempotenza: se rilanci il playbook, ricrei pulito.

**File to create:** `$LAB_DIR/task3/seed.sql`

```sql
DROP TABLE IF EXISTS cars;
CREATE TABLE cars (
  id         INT AUTO_INCREMENT PRIMARY KEY,
  brand      VARCHAR(50)    NOT NULL,
  model      VARCHAR(80)    NOT NULL,
  year       YEAR           NOT NULL,
  price_eur  DECIMAL(10,2)  NOT NULL,
  hp         INT            NOT NULL,
  km         INT            NOT NULL
);

INSERT INTO cars (brand, model, year, price_eur, hp, km) VALUES
  ('Ferrari',    '488 GTB',         2018, 215000.00, 661, 18500),
  ('Ferrari',    'Roma',            2021, 198000.00, 620, 12000),
  ('Porsche',    '911 Carrera S',   2020, 132000.00, 450, 24300),
  ('Porsche',    'Cayman GT4',      2019,  95000.00, 420, 31000),
  ('BMW',        'M3 Competition',  2022,  88000.00, 510,  8200),
  ('BMW',        '330d Touring',    2020,  42000.00, 286, 65000),
  ('Volkswagen', 'Golf GTI',        2021,  38500.00, 245, 22000),
  ('Volkswagen', 'Passat Variant',  2019,  28900.00, 190, 78000),
  ('Toyota',     'Supra GR',        2022,  72000.00, 340,  5400),
  ('Toyota',     'Corolla Hybrid',  2023,  28500.00, 122,  9800);
```

Template PHP. Jinja2 sostituisce `{{ rds_host }}`, `{{ db_user }}`, ecc., con
i valori passati da Ansible. Il PHP apre una connessione MySQLi, esegue la
query e renderizza una tabella HTML stilizzata.

**File to create:** `$LAB_DIR/task3/templates/test.php.j2`

```php
<?php
$host = '{{ rds_host }}';
$user = '{{ db_user }}';
$pass = '{{ db_pass }}';
$db   = '{{ db_name }}';

$mysqli = @new mysqli($host, $user, $pass, $db);
if ($mysqli->connect_errno) {
    http_response_code(500);
    echo "DB connection failed: " . htmlspecialchars($mysqli->connect_error);
    exit;
}

$result = $mysqli->query("SELECT brand, model, year, price_eur, hp, km FROM cars ORDER BY price_eur DESC");
?>
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Lab cars inventory</title>
<style>
  body { font-family: Arial, sans-serif; margin: 2em; background: #fafafa; color: #222; }
  h1   { color: #333; }
  table { border-collapse: collapse; width: 100%; max-width: 960px; box-shadow: 0 1px 2px #ccc; }
  th, td { border: 1px solid #ccc; padding: 8px 12px; text-align: left; }
  th { background: #333; color: #fff; }
  tr:nth-child(even) td { background: #f0f0f0; }
  td.num { text-align: right; }
</style>
</head>
<body>
<h1>Cars inventory</h1>
<table>
<thead>
  <tr><th>Brand</th><th>Model</th><th>Year</th><th>Price (€)</th><th>HP</th><th>Mileage (km)</th></tr>
</thead>
<tbody>
<?php while ($row = $result->fetch_assoc()): ?>
  <tr>
    <td><?= htmlspecialchars($row['brand']) ?></td>
    <td><?= htmlspecialchars($row['model']) ?></td>
    <td class="num"><?= htmlspecialchars($row['year']) ?></td>
    <td class="num"><?= number_format((float)$row['price_eur'], 2, ',', '.') ?></td>
    <td class="num"><?= htmlspecialchars($row['hp']) ?></td>
    <td class="num"><?= number_format((int)$row['km'], 0, ',', '.') ?></td>
  </tr>
<?php endwhile; ?>
</tbody>
</table>
</body>
</html>
<?php $mysqli->close(); ?>
```

Playbook Ansible. Due play: il primo configura entrambe le EC2 (httpd + PHP +
template); il secondo, eseguito solo sulla prima EC2 (`run_once: true`), applica
lo schema e i dati al DB. Mariadb105 fornisce il client `mysql` su AL2023.

**File to create:** `$LAB_DIR/task3/configure.yml`

```yaml
---
- name: Configure web tier (httpd + PHP + test.php)
  hosts: web
  become: true
  gather_facts: false
  vars:
    rds_host: "{{ lookup('env', 'RDS_HOST') }}"
    db_user:  "{{ lookup('env', 'DB_USER') }}"
    db_pass:  "{{ lookup('env', 'DB_PASS') }}"
    db_name:  "labdb"
  tasks:
    - name: Wait for SSH
      ansible.builtin.wait_for_connection:
        timeout: 300

    - name: Install httpd, PHP 8.2 and mysql client
      ansible.builtin.dnf:
        name:
          - httpd
          - php
          - php-mysqlnd
          - mariadb105
        state: present

    - name: Enable and start httpd
      ansible.builtin.service:
        name: httpd
        state: started
        enabled: true

    - name: Deploy test.php from template
      ansible.builtin.template:
        src: templates/test.php.j2
        dest: /var/www/html/test.php
        owner: apache
        group: apache
        mode: '0644'

- name: Seed RDS schema and data (from web_a only)
  hosts: web
  become: false
  gather_facts: false
  run_once: true
  vars:
    rds_host: "{{ lookup('env', 'RDS_HOST') }}"
    db_user:  "{{ lookup('env', 'DB_USER') }}"
    db_pass:  "{{ lookup('env', 'DB_PASS') }}"
  tasks:
    - name: Copy seed.sql to remote
      ansible.builtin.copy:
        src: seed.sql
        dest: /tmp/seed.sql
        mode: '0600'

    - name: Apply schema and seed data on RDS
      ansible.builtin.shell: >
        mysql -h {{ rds_host }} -u {{ db_user }} -p'{{ db_pass }}' labdb < /tmp/seed.sql
      args:
        executable: /bin/bash
      no_log: true
```

Esportiamo gli output Terraform come variabili d'ambiente che il playbook
legge via `lookup('env', ...)`.

```bash
export RDS_HOST="$(terraform output -raw rds_address)"
export DB_USER="labadmin"
export DB_PASS="$DB_PASSWORD"
```

Eseguiamo il playbook.

```bash
ansible-playbook -i inventory.ini configure.yml
```

L'ALB impiega ~30-60 secondi a marcare i target come healthy. Aspettiamo
attivamente, con un loop che esce appena la curl restituisce 200.

```bash
ALB_DNS="$(terraform output -raw alb_dns_name)"
echo "ALB DNS: $ALB_DNS"
for i in $(seq 1 30); do
  if curl -sfo /dev/null "http://${ALB_DNS}/test.php"; then
    echo "ALB ready after $((i*10))s"
    break
  fi
  sleep 10
done
```

**Expected outcome:** Terraform ha creato la VPC, i SG, l'ALB, due EC2 e RDS.
Ansible ha installato httpd + PHP e deployato `test.php` su entrambe le EC2,
poi ha caricato la tabella `cars` con 10 righe su RDS. Una `curl` a
`http://<ALB_DNS>/test.php` restituisce HTTP 200 con la tabella HTML.

**Success criterion:**

```bash
cd "$LAB_DIR/task3" && RESPONSE="$(curl -sfL "http://$(terraform output -raw alb_dns_name)/test.php")" \
  && echo "$RESPONSE" | grep -q '<table' \
  && echo "$RESPONSE" | grep -Eq 'Ferrari|BMW|Porsche|Volkswagen|Toyota'
```

## Task 4 — Security scanning con tfsec

**Goal:** Installare tfsec, scansionare il codice di Task 3, capire ogni
finding HIGH/CRITICAL, applicare i fix strutturali nel codice e — dove un fix
romperebbe la coerenza del lab (es. HTTPS senza certificato) — applicare un
ignore esplicito e motivato. L'obiettivo finale è che
`tfsec . --minimum-severity HIGH` esca con 0.

**Steps:**

Lavoriamo nella stessa cartella di Task 3.

```bash
cd "$LAB_DIR/task3"
```

Installiamo tfsec. Il metodo dipende dalla piattaforma host: macOS via brew,
Linux via script ufficiale.

```bash
# macOS:
if command -v brew >/dev/null 2>&1; then
  brew install tfsec
else
  # Linux: binary install via script ufficiale
  curl -sL https://raw.githubusercontent.com/aquasecurity/tfsec/master/scripts/install_linux.sh | sudo bash
fi
tfsec --version
```

Eseguiamo lo scan iniziale e salviamo l'output. Senza `--minimum-severity`
vediamo tutto, così abbiamo il contesto pieno.

```bash
tfsec . --no-color > /tmp/tfsec_before.txt || true
head -80 /tmp/tfsec_before.txt
```

Conteggio finding HIGH/CRITICAL prima dei fix, per misurare progressi:

```bash
tfsec . --no-color --minimum-severity HIGH 2>/dev/null | grep -Ec '^\s*(Severity|severity)\s*:\s*(HIGH|CRITICAL)$' || true
```

Ora applichiamo i fix. Modificheremo `main.tf` aggiungendo blocchi e attributi.

**Fix 1 — RDS: abilitare encryption at rest.**

L'attributo `storage_encrypted = true` abilita la cifratura del volume EBS
sottostante a RDS con la chiave KMS gestita di default (`aws/rds`). Senza
KMS key esplicita usiamo quella di servizio: zero costo aggiuntivo, copre il
finding `aws-rds-encrypt-instance-storage-data`.

Modifica `aws_db_instance.lab` aggiungendo `storage_encrypted = true` subito
sotto `allocated_storage`:

```hcl
resource "aws_db_instance" "lab" {
  identifier             = "terraform-lab-db"
  engine                 = "mysql"
  engine_version         = "8.0"
  instance_class         = "db.t3.micro"
  allocated_storage      = 20
  storage_encrypted      = true     # ← aggiungere
  db_name                = "labdb"
  # ... resto invariato
}
```

Applica la modifica al file:

```bash
sed -i.bak '/allocated_storage      = 20$/a\
  storage_encrypted      = true
' main.tf
```

**Fix 2 — EC2: imporre IMDSv2 (token obbligatorio).**

IMDSv1 espone i metadati EC2 senza autenticazione, vulnerabilità classica
sfruttata da SSRF. IMDSv2 impone un token Session via PUT, mitigando l'attacco.
Il blocco `metadata_options { http_tokens = "required" }` chiude il finding
`aws-ec2-enforce-http-token-imds`.

Aggiungi il blocco `metadata_options` a entrambe le risorse `aws_instance`.
Ecco la sostituzione automatica:

```bash
python3 <<'PY'
import re
p = "main.tf"
src = open(p).read()
block = (
    '\n  metadata_options {\n'
    '    http_tokens                 = "required"\n'
    '    http_endpoint               = "enabled"\n'
    '    http_put_response_hop_limit = 1\n'
    '  }\n'
)
# Inserisce metadata_options prima della chiusura dei due aws_instance
src = re.sub(
    r'(resource "aws_instance" "web_[ab]" \{[^}]*?associate_public_ip_address = true\n)',
    r'\1' + block,
    src,
)
open(p, "w").write(src)
PY
```

**Fix 3 — ALB: drop invalid header fields.**

`drop_invalid_header_fields = true` istruisce l'ALB a scartare richieste con
header HTTP malformati, evitando smuggling. Chiude il finding
`aws-elb-drop-invalid-headers`.

Aggiungi l'attributo al blocco `aws_lb "lab"`:

```bash
sed -i.bak '/security_groups    = \[aws_security_group.alb.id\]$/a\
  drop_invalid_header_fields = true
' main.tf
```

**Fix 4 — ALB: abilitare access logs.**

L'ALB scrive i log di accesso in un bucket S3. Servono: un bucket, una bucket
policy che autorizzi l'ELB service account a fare PutObject, e l'attributo
`access_logs` sull'ALB. Aggiungiamo anche encryption, blocco public access e
versioning sul bucket per non generare nuovi finding tfsec sul bucket stesso.

Aggiungiamo in fondo a `main.tf` i blocchi seguenti:

```bash
cat >> main.tf <<'HCL'

# ----------------------------------------------------------------------------
# Task 4: S3 bucket per ALB access logs.
# ELB service account di regione, recuperato dinamicamente.
# ----------------------------------------------------------------------------

data "aws_elb_service_account" "main" {}

resource "random_id" "alb_logs_suffix" {
  byte_length = 4
}

resource "aws_s3_bucket" "alb_logs" {
  bucket        = "terraform-lab-alb-logs-${random_id.alb_logs_suffix.hex}"
  force_destroy = true

  tags = {
    Name = "terraform-lab-alb-logs"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "alb_logs" {
  bucket = aws_s3_bucket.alb_logs.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "alb_logs" {
  bucket                  = aws_s3_bucket.alb_logs.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "alb_logs" {
  bucket = aws_s3_bucket.alb_logs.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_policy" "alb_logs" {
  bucket = aws_s3_bucket.alb_logs.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { AWS = data.aws_elb_service_account.main.arn }
      Action    = "s3:PutObject"
      Resource  = "${aws_s3_bucket.alb_logs.arn}/*"
    }]
  })
}
HCL
```

Aggiungiamo il blocco `access_logs` all'ALB. Lo facciamo con una piccola
sostituzione Python che inserisce prima del `tags = {` finale del blocco
`aws_lb "lab"`:

```bash
python3 <<'PY'
p = "main.tf"
src = open(p).read()
needle = 'drop_invalid_header_fields = true\n'
insert = (
    needle +
    '\n  access_logs {\n'
    '    bucket  = aws_s3_bucket.alb_logs.id\n'
    '    enabled = true\n'
    '    prefix  = "alb"\n'
    '  }\n'
)
src = src.replace(needle, insert, 1)
open(p, "w").write(src)
PY
```

**Fix 5 — Ignore espliciti per finding incompatibili con il lab.**

Tre finding HIGH non si possono chiudere strutturalmente senza rompere lo
scopo del lab:

- `aws-elb-alb-not-public`: il lab espone deliberatamente un ALB pubblico.
- `aws-elb-http-not-used`: non disponiamo di un dominio registrato e di un
  certificato ACM per fornire HTTPS, il lab usa HTTP.
- `aws-ec2-no-public-ip-subnet`: le subnet sono volutamente pubbliche per
  evitare il costo di un NAT Gateway in un lab di 3 ore.

tfsec supporta ignore commenti `#tfsec:ignore:<rule>` con motivazione. Inseriamoli
sopra le righe interessate.

```bash
python3 <<'PY'
p = "main.tf"
src = open(p).read()

# Ignore ALB pubblica: sopra il blocco aws_lb "lab"
src = src.replace(
    'resource "aws_lb" "lab" {',
    '# tfsec:ignore:aws-elb-alb-not-public lab espone deliberatamente un ALB pubblico\n'
    'resource "aws_lb" "lab" {',
    1,
)

# Ignore HTTP listener: sopra il blocco aws_lb_listener "http"
src = src.replace(
    'resource "aws_lb_listener" "http" {',
    '# tfsec:ignore:aws-elb-http-not-used lab senza dominio/cert ACM, HTTPS fuori scope\n'
    'resource "aws_lb_listener" "http" {',
    1,
)

# Ignore subnet pubbliche
src = src.replace(
    'resource "aws_subnet" "public_a" {',
    '# tfsec:ignore:aws-ec2-no-public-ip-subnet subnet pubblica voluta per evitare NAT GW nel lab\n'
    'resource "aws_subnet" "public_a" {',
    1,
)
src = src.replace(
    'resource "aws_subnet" "public_b" {',
    '# tfsec:ignore:aws-ec2-no-public-ip-subnet subnet pubblica voluta per evitare NAT GW nel lab\n'
    'resource "aws_subnet" "public_b" {',
    1,
)

open(p, "w").write(src)
PY
```

Validiamo che il file sia ancora HCL valido, poi applichiamo. RDS impiega
qualche minuto per riapplicare encryption (in realtà se RDS è già stato
creato non cifrato, abilitare `storage_encrypted` forza la sostituzione
dell'istanza; per il lab è accettabile).

```bash
terraform fmt
terraform validate
terraform apply -auto-approve
```

Rilanciamo tfsec con `--minimum-severity HIGH`. Se l'exit code è 0 i fix sono
sufficienti per il livello richiesto.

```bash
tfsec . --minimum-severity HIGH --no-color | tee /tmp/tfsec_after.txt
echo "tfsec exit code: $?"
```

**Expected outcome:** `main.tf` ora contiene `storage_encrypted = true` su
RDS, `metadata_options` con IMDSv2 sulle EC2, `drop_invalid_header_fields` e
`access_logs` sull'ALB, un bucket S3 cifrato con bucket policy per i log, e
ignore commentati su tre finding strutturalmente incompatibili con il lab.
`tfsec . --minimum-severity HIGH` esce con 0.

**Success criterion:**

```bash
cd "$LAB_DIR/task3" && tfsec . --minimum-severity HIGH --no-color >/dev/null 2>&1
```

## Cleanup

Distruggiamo lo stack di Task 3/4 (l'unico ancora vivo, Task 1 e Task 2 sono
già stati distrutti dentro i rispettivi task). RDS impiega ~5 minuti a
cancellarsi.

```bash
cd "$LAB_DIR/task3" && terraform destroy -auto-approve
```

Verifichiamo che nessuna risorsa con tag `lab=terraform-aws` rimanga. Il tag
copre tutto perché abbiamo usato `default_tags` nel provider.

```bash
aws resourcegroupstaggingapi get-resources \
  --tag-filters Key=lab,Values=terraform-aws \
  --region "$AWS_REGION" \
  --query 'ResourceTagMappingList[].ResourceARN' \
  --output table
```

Il comando deve restituire una tabella vuota (o l'header senza righe). Se
qualche risorsa è rimasta, prendi nota dell'ARN e cancellala manualmente:
solitamente sono security group orfani trattenuti da ENI in stato `detaching`.

Rimuoviamo la cartella di lavoro locale e i file temporanei:

```bash
rm -rf "$LAB_DIR"
rm -f /tmp/task1_instance_id.txt /tmp/tfsec_before.txt /tmp/tfsec_after.txt
```

Le variabili d'ambiente esportate in Setup esistono solo per la shell corrente
e scompaiono alla sua chiusura. Niente da pulire altrove.

## Troubleshooting

_Sezione lasciata vuota per la prima emissione. Verrà popolata
dall'Evaluator nei cicli successivi sulla base dei problemi reali emersi
durante l'esecuzione del lab._

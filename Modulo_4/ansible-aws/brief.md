# Workshop: Deploy a Spring Boot Application on AWS EC2 with Ansible

## Overview

In this workshop, you will write an Ansible playbook to provision an Amazon EC2 instance and deploy a Java Spring Boot application cloned from a GitHub repository. By the end, you will have a running application accessible via the instance's public IP.

## Objectives

- Launch and configure an EC2 instance using Ansible
- Install Java and required dependencies
- Clone a Spring Boot application from GitHub
- Build and run the application as a background service

## Prerequisites

- AWS account with programmatic access (Access Key + Secret Key)
- Ansible installed locally (`pip install ansible boto3 botocore`)
- An existing EC2 Key Pair in your target region
- Python 3.x on the control node

## Lab Steps

### 1. Configure AWS Credentials

Export your credentials so Ansible's AWS modules can authenticate:

```bash
export AWS_ACCESS_KEY_ID=<your-access-key>
export AWS_SECRET_ACCESS_KEY=<your-secret-key>
export AWS_DEFAULT_REGION=eu-west-1
```

### 2. Project Structure

```
ansible-aws/
├── inventory/
│   └── aws_ec2.yml        # Dynamic inventory using aws_ec2 plugin
├── playbooks/
│   └── deploy.yml         # Main playbook
├── roles/
│   └── springboot/
│       └── tasks/
│           └── main.yml   # Role tasks
└── ansible.cfg
```

### 3. Dynamic Inventory (`inventory/aws_ec2.yml`)

```yaml
plugin: amazon.aws.aws_ec2
regions:
  - eu-west-1
filters:
  tag:workshop: ansible-lab
keyed_groups:
  - key: tags.Name
    prefix: tag
```

### 4. Main Playbook (`playbooks/deploy.yml`)

```yaml
---
- name: Provision EC2 instance
  hosts: localhost
  gather_facts: false
  tasks:
    - name: Launch EC2 instance
      amazon.aws.ec2_instance:
        name: ansible-lab-instance
        key_name: <your-key-pair>
        instance_type: t3.micro
        image_id: ami-0c38b837cd80f13bb   # Amazon Linux 2023, eu-west-1
        security_groups: [default]
        tags:
          workshop: ansible-lab
        wait: true
        state: running
      register: ec2

    - name: Add instance to in-memory group
      ansible.builtin.add_host:
        hostname: "{{ ec2.instances[0].public_ip_address }}"
        groups: app_servers
        ansible_user: ec2-user
        ansible_ssh_private_key_file: ~/.ssh/<your-key-pair>.pem

    - name: Wait for SSH
      ansible.builtin.wait_for:
        host: "{{ ec2.instances[0].public_ip_address }}"
        port: 22
        timeout: 60

- name: Deploy Spring Boot application
  hosts: app_servers
  become: true
  roles:
    - springboot
```

### 5. Role Tasks (`roles/springboot/tasks/main.yml`)

```yaml
---
- name: Install Java 21 and Git
  ansible.builtin.package:
    name:
      - java-21-amazon-corretto-headless
      - git
      - maven
    state: present

- name: Clone application repository
  ansible.builtin.git:
    repo: https://github.com/<org>/<repo>.git
    dest: /opt/app
    version: main
    force: true

- name: Build application
  ansible.builtin.command:
    cmd: mvn package -DskipTests
    chdir: /opt/app
  changed_when: true

- name: Run application
  ansible.builtin.shell: |
    nohup java -jar target/*.jar > /var/log/springboot.log 2>&1 &
  args:
    chdir: /opt/app
  changed_when: true
```

### 6. Run the Playbook

```bash
ansible-playbook playbooks/deploy.yml -i inventory/aws_ec2.yml
```

### 7. Verify

```bash
curl http://<instance-public-ip>:8080
```

## Challenge Tasks

1. Parameterise the GitHub repo URL and branch as Ansible variables
2. Create a `systemd` service unit for the Spring Boot app so it survives reboots
3. Add a security group rule that opens port 8080 only to your IP
4. Use Ansible Vault to store any sensitive variables

## Clean Up

Terminate the instance to avoid charges:

```bash
ansible -i inventory/aws_ec2.yml app_servers -m amazon.aws.ec2_instance \
  -a "state=terminated filters={tag:workshop: ansible-lab}" --become
```

---

**Duration:** ~90 minutes  
**Difficulty:** Intermediate

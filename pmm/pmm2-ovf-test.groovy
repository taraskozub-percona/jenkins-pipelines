library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runUITests(CLIENT_VERSION, CLIENT_INSTANCE, SERVER_IP, GIT_BRANCH, CLIENTS) {
    stagingJob = build job: 'pmm2-ui-tests', parameters: [
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENT_INSTANCE', value: CLIENT_INSTANCE),
        string(name: 'SERVER_IP', value: SERVER_IP),
        string(name: 'OVF_TEST', value: 'yes'),
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'ADMIN_PASSWORD', value: "admin")
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    env.ADMIN_PASSWORD = "admin"
    env.PMM_URL = "http://admin:${ADMIN_PASSWORD}@${SERVER_IP}"
    env.PMM_UI_URL = "https://${SERVER_IP}"
}

pipeline {
    agent {
        label 'ovf-do'
    }
    parameters {
        string(
            defaultValue: 'dev-latest',
            description: 'PMM2 Client version',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: 'PMM2-Server-dev-latest.ova',
            description: 'OVA Image version',
            name: 'OVA_VERSION')
        string(
            defaultValue: 'dev-latest',
            description: 'Repositry to fetch Latest Image from',
            name: 'REPO')
        string(
            defaultValue: '',
            description: 'public ssh key for "ec2-user" user, please set if you need ssh access',
            name: 'SSH_KEY')
        string(
            defaultValue: '5.7',
            description: 'Percona XtraDB Cluster version',
            name: 'PXC_VERSION')
        string(
            defaultValue: '5.7',
            description: 'Percona Server for MySQL version',
            name: 'PS_VERSION')
        string(
            defaultValue: '8.0',
            description: 'MySQL Community Server version',
            name: 'MS_VERSION')
        string(
            defaultValue: '10.3',
            description: 'MariaDB Server version',
            name: 'MD_VERSION')
        string(
            defaultValue: '4.0',
            description: 'Percona Server for MongoDB version',
            name: 'MO_VERSION')
        string(
            defaultValue: '10.7',
            description: 'Postgre SQL Server version',
            name: 'PGSQL_VERSION')
        string(
            defaultValue: '--addclient=haproxy,1 --setup-external-service --mongo-replica-for-backup',
            description: 'Configure PMM Clients. ps - Percona Server for MySQL, pxc - Percona XtraDB Cluster, ms - MySQL Community Server, md - MariaDB Server, MO - Percona Server for MongoDB, pgsql - Postgre SQL Server',
            name: 'CLIENTS')
        string(
            defaultValue: 'true',
            description: 'Enable Slack notification (option for high level pipelines)',
            name: 'NOTIFY')
        string(
            defaultValue: 'true',
            description: 'Use this OVA Setup as PMM-client',
            name: 'SETUP_CLIENT')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-ui-tests repository',
            name: 'GIT_BRANCH')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers { cron('0 22 * * *') }
    stages {
        stage('Prepare') {
            steps {
                deleteDir()
                withCredentials([usernamePassword(credentialsId: 'Jenkins API', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        curl -s -u ${USER}:${PASS} ${BUILD_URL}api/json \
                            | python -c "import sys, json; print json.load(sys.stdin)['actions'][1]['causes'][0]['userId']" \
                            | sed -e 's/@percona.com//' \
                            > OWNER
                        echo "pmm-\$(cat OWNER | cut -d . -f 1)-\$(date -u '+%Y%m%d%H%M')" \
                            > VM_NAME
                    """
                }
                stash includes: 'OWNER,VM_NAME', name: 'VM_NAME'
                script {
                    def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()
                    echo """
                        PXC_VERSION:    ${PXC_VERSION}
                        PS_VERSION:     ${PS_VERSION}
                        MS_VERSION:     ${MS_VERSION}
                        MD_VERSION:     ${MD_VERSION}
                        MO_VERSION:     ${MO_VERSION}
                        PGSQL_VERSION:  ${PGSQL_VERSION}
                        CLIENTS:        ${CLIENTS}
                        OWNER:          ${OWNER}
                    """
                    if ("${NOTIFY}" == "true") {
                        slackSend botUser: true, channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                        slackSend botUser: true, channel: "@${OWNER}", color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                    }
                }
            }
        }
        stage('Run PMM-Server') {
            steps {
                unstash 'VM_NAME'
                sh """
                    sudo mkdir -p /srv/pmm-qa || :
                    pushd /srv/pmm-qa
                        sudo git clone https://github.com/percona/pmm-qa.git .
                        sudo svn export https://github.com/Percona-QA/percona-qa.git/trunk/get_download_link.sh
                        sudo chmod 755 get_download_link.sh
                    popd
                    sudo git clone --single-branch --branch \${GIT_BRANCH} https://github.com/percona/pmm-ui-tests.git
                    pushd pmm-ui-tests
                    PWD=\$(pwd) docker-compose up -d mysql
                    PWD=\$(pwd) docker-compose up -d mongo
                    PWD=\$(pwd) docker-compose up -d postgres
                    PWD=\$(pwd) docker-compose up -d proxysql
                    popd
                """
                waitForContainer('pmm-agent_mongo', 'waiting for connections on port 27017')
                waitForContainer('pmm-agent_mysql_5_7', "Server hostname (bind-address):")
                waitForContainer('pmm-agent_postgres', 'PostgreSQL init process complete; ready for start up.')
                sh """
                    pushd pmm-ui-tests
                    bash -x testdata/db_setup.sh
                    popd
                """
                sh '''
                    wget -O \$(cat VM_NAME).ova http://percona-vm.s3-website-us-east-1.amazonaws.com/\${OVA_VERSION} > /dev/null
                '''
                sh '''
                    export VM_NAME=\$(cat VM_NAME)
                    export OWNER=\$(cat OWNER)

                    export BUILD_ID=dear-jenkins-please-dont-kill-virtualbox
                    export JENKINS_NODE_COOKIE=dear-jenkins-please-dont-kill-virtualbox
                    export JENKINS_SERVER_COOKIE=dear-jenkins-please-dont-kill-virtualbox

                    tar xvf \$VM_NAME.ova
                    export ovf_name=$(find -type f -name '*.ovf');
                    export VM_MEMORY=4096
                    VBoxManage import \$ovf_name --vsys 0 --memory \$VM_MEMORY --vmname \$VM_NAME > /dev/null
                    VBoxManage modifyvm \$VM_NAME \
                        --memory \$VM_MEMORY \
                        --audio none \
                        --natpf1 "guestssh,tcp,,80,,80" \
                        --uart1 0x3F8 4 --uartmode1 file /tmp/\$VM_NAME-console.log \
                        --groups "/\$OWNER,/${JOB_NAME}"
                    VBoxManage modifyvm \$VM_NAME --natpf1 "guesthttps,tcp,,443,,443"
                    for p in $(seq 0 15); do
                        VBoxManage modifyvm \$VM_NAME --natpf1 "guestexporters\$p,tcp,,4200\$p,,4200\$p"
                    done
                    VBoxManage startvm --type headless \$VM_NAME
                    sleep 180
                    cat /tmp/\$VM_NAME-console.log
                    for I in $(seq 1 6); do
                        IP=\$(grep eth0 /tmp/\$VM_NAME-console.log | cut -d '|' -f 4 | sed -e 's/ //g' | head -n 1)
                        if [ -n "\$IP" ]; then
                            break
                        fi
                        sleep 10
                    done
                    echo \$IP > IP
                    PUBIP=\$(curl ifconfig.me)
                    echo \$PUBIP > PUBLIC_IP
                    cat PUBLIC_IP
                    if [ "X\$IP" = "X." ]; then
                        echo Error during DHCP configure. exiting
                        exit 1
                    fi
                    sleep 120
                '''
                withCredentials([usernamePassword(credentialsId: 'Jenkins API', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        set +x
                        export VM_NAME=\$(cat VM_NAME)

                        mkdir -p /tmp/\$VM_NAME
                        rm -rf /tmp/\$VM_NAME/sshkey
                        touch /tmp/\$VM_NAME/sshkey

                        yes y | ssh-keygen -f "/tmp/\$VM_NAME/sshkey" -N "" > /dev/null
                        cat "/tmp/\$VM_NAME/sshkey.pub" > PUB_KEY
                        chmod 600 /tmp/\$VM_NAME/sshkey
                        set -x
                    """
                }
                stash includes: 'PUB_KEY', name: 'PUB_ACCESS'
                script {
                    env.IP      = sh(returnStdout: true, script: "cat IP | cut -f1 -d' '").trim()
                    env.PUBLIC_IP = sh(returnStdout: true, script: "cat PUBLIC_IP").trim()
                    env.VM_NAME = sh(returnStdout: true, script: "cat VM_NAME").trim()
                    env.PUB_KEY = sh(returnStdout: true, script: "cat PUB_KEY").trim()
                    env.OWNER   = sh(returnStdout: true, script: "cat OWNER | cut -d . -f 1").trim()
                    env.ADMIN_PASSWORD = "admin"
                }
                setupPMMClient(env.PUBLIC_IP, CLIENT_VERSION, 'pmm2', 'yes', 'no', 'yes', 'ovf_setup', env.ADMIN_PASSWORD)
                sh """
                    set -o errexit
                    set -o xtrace
                    export PATH=\$PATH:/usr/sbin
                    if [[ \$CLIENT_VERSION != dev-latest ]]; then
                        export PATH="`pwd`/pmm2-client/bin:$PATH"
                    fi
                    bash /srv/pmm-qa/pmm-tests/pmm-framework.sh \
                        --download \
                        --addclient=haproxy,1 --setup-external-service \
                        --pmm2 \
                        --pmm2-server-ip=\$PUBLIC_IP
                    sleep 10
                    pmm-admin list
                """
                archiveArtifacts 'PUBLIC_IP'
                archiveArtifacts 'VM_NAME'
            }
        }
        stage('Start UI Tests') {
            steps {
                runUITests(CLIENT_VERSION, 'yes', "${env.PUBLIC_IP}", GIT_BRANCH, CLIENTS)
            }
        }
    }

    post {
        always {
            sh '''
                pushd pmm-ui-tests
                docker-compose down
                docker rm -f $(sudo docker ps -a -q) || true
                docker volume rm $(sudo docker volume ls -q) || true
                popd
            '''
        }
        success {
            script {
                sh '''
                    export VM_NAME=\$(cat VM_NAME)
                    if [ -n "$VM_NAME" ]; then
                        VBoxManage controlvm $VM_NAME poweroff
                        sleep 10
                        VBoxManage unregistervm --delete $VM_NAME
                        rm -r /tmp/$VM_NAME
                    fi
                '''
                if ("${NOTIFY}" == "true") {
                    def PUBLIC_IP = sh(returnStdout: true, script: "cat PUBLIC_IP").trim()
                    def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()

                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - https://${PUBLIC_IP}"
                    slackSend botUser: true, channel: "@${OWNER}", color: '#00FF00', message: "[${JOB_NAME}]: build finished - https://${PUBLIC_IP}"
                }
            }
        }
        failure {
            sh '''
                export VM_NAME=\$(cat VM_NAME)
                if [ -n "$VM_NAME" ]; then
                    VBoxManage controlvm $VM_NAME poweroff
                    sleep 10
                    VBoxManage unregistervm --delete $VM_NAME
                    rm -r /tmp/$VM_NAME
                fi
            '''
            script {
                if ("${NOTIFY}" == "true") {
                    def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()

                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build failed"
                    slackSend botUser: true, channel: "@${OWNER}", color: '#FF0000', message: "[${JOB_NAME}]: build failed"
                }
            }
        }
    }
}

// Java Demo App — full CI/CD pipeline
// Stages: Maven build → Kaniko image → Trivy scan → Zarf package → RustFS → deploy → ArgoCD

pipeline {
    agent {
        kubernetes {
            label "java-build-${UUID.randomUUID().toString().take(8)}"
            defaultContainer 'maven'
            yaml """
apiVersion: v1
kind: Pod
spec:
  serviceAccountName: jenkins
  containers:

    # Maven — compiles Java source and produces the JAR
    - name: maven
      image: maven:3.9-eclipse-temurin-17
      command: [sleep]
      args: [infinity]
      env:
        - name: HOME
          value: /tmp
      volumeMounts:
        - name: workspace
          mountPath: /workspace
        - name: maven-cache
          mountPath: /root/.m2

    # Kaniko — builds Docker image without Docker daemon and pushes to Zarf registry
    - name: kaniko
      image: gcr.io/kaniko-project/executor:debug
      command: [sleep]
      args: [infinity]
      env:
        - name: HOME
          value: /tmp
      volumeMounts:
        - name: workspace
          mountPath: /workspace
        - name: kaniko-config
          mountPath: /kaniko/.docker

    # Trivy — scans the built image for vulnerabilities
    - name: trivy
      image: aquasec/trivy:0.51.1
      command: [sleep]
      args: [infinity]
      env:
        - name: HOME
          value: /tmp
        - name: TRIVY_CACHE_DIR
          value: /tmp/trivy-cache
      volumeMounts:
        - name: trivy-cache
          mountPath: /tmp/trivy-cache

    # Builder — Zarf, AWS CLI, kubectl, git operations
    - name: builder
      image: alpine/git:2.43.0
      command: [sleep]
      args: [infinity]
      env:
        - name: HOME
          value: /tmp
      volumeMounts:
        - name: workspace
          mountPath: /workspace

  volumes:
    - name: workspace
      emptyDir: {}
    - name: maven-cache
      emptyDir: {}
    - name: trivy-cache
      emptyDir: {}
    # Docker config for Kaniko to authenticate with Zarf internal registry
    - name: kaniko-config
      secret:
        secretName: zarf-registry-kaniko
"""
        }
    }

    parameters {
        string(
            name: 'SERVICE_VERSION',
            defaultValue: '1.0.0',
            description: 'Version tag for the Docker image and Zarf package'
        )
        choice(
            name: 'TRIVY_SEVERITY',
            choices: ['CRITICAL', 'CRITICAL,HIGH', 'CRITICAL,HIGH,MEDIUM'],
            description: 'Fail the build if CVEs of this severity are found'
        )
        booleanParam(
            name: 'TRIVY_FAIL_ON_VULN',
            defaultValue: true,
            description: 'Fail the pipeline if Trivy finds vulnerabilities'
        )
        booleanParam(
            name: 'UPDATE_ARGOCD',
            defaultValue: true,
            description: 'Push updated image tag to ArgoCD repo'
        )
    }

    environment {
        ZARF_VERSION       = "${env.ZARF_VERSION ?: 'v0.75.0'}"
        RUSTFS_URL         = "${env.RUSTFS_URL ?: 'http://192.168.1.246:9000'}"
        RUSTFS_BUCKET      = "${env.RUSTFS_BUCKET ?: 'zarf-packages'}"
        ARGOCD_REPO        = "${env.ARGOCD_REPO ?: 'git@github.com:azavadsk/argocd.git'}"
        ARGOCD_REPO_BRANCH = "${env.ARGOCD_REPO_BRANCH ?: 'main'}"
        ZARF_REGISTRY      = "192.168.1.233:31999"
        IMAGE_NAME         = "java-demo-app"
        IMAGE_FULL         = "192.168.1.233:31999/java-demo-app:${params.SERVICE_VERSION}"
        PACKAGE_NAME       = "zarf-package-java-demo-app-amd64-${params.SERVICE_VERSION}.tar.zst"
    }

    stages {

        stage('Checkout') {
            steps {
                container('maven') {
                    sh "cp -r . /workspace/ 2>/dev/null || true"
                }
            }
        }

        stage('Maven Build') {
            steps {
                container('maven') {
                    sh """
                        cd /workspace
                        echo "Building Java app v${params.SERVICE_VERSION}..."
                        mvn package -DskipTests -q
                        ls -lh target/java-demo-app.jar
                    """
                }
            }
        }

        stage('Build Docker Image — Kaniko') {
            steps {
                container('kaniko') {
                    sh """
                        echo "Building Docker image: ${IMAGE_FULL}"
                        /kaniko/executor \
                            --context=dir:///workspace \
                            --dockerfile=/workspace/Dockerfile \
                            --destination=${IMAGE_FULL} \
                            --insecure \
                            --skip-tls-verify \
                            --cache=false
                        echo "Image pushed to Zarf registry: ${IMAGE_FULL}"
                    """
                }
            }
        }

        stage('Scan Image — Trivy') {
            steps {
                container('trivy') {
                    sh """
                        echo "=== Trivy Scan: ${IMAGE_FULL} ==="

                        trivy image --download-db-only

                        trivy image \
                            --severity ${params.TRIVY_SEVERITY} \
                            --ignore-unfixed \
                            --insecure \
                            --format table \
                            --output /tmp/trivy-report.txt \
                            ${IMAGE_FULL} || true

                        cat /tmp/trivy-report.txt

                        trivy image \
                            --severity ${params.TRIVY_SEVERITY} \
                            --ignore-unfixed \
                            --insecure \
                            --format json \
                            --output /tmp/trivy-report.json \
                            ${IMAGE_FULL} || true

                        if [ "${params.TRIVY_FAIL_ON_VULN}" = "true" ]; then
                            trivy image \
                                --severity ${params.TRIVY_SEVERITY} \
                                --ignore-unfixed \
                                --insecure \
                                --exit-code 1 \
                                ${IMAGE_FULL}
                        fi

                        echo "=== Trivy scan passed ==="
                    """
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: '/tmp/trivy-report.*', allowEmptyArchive: true
                }
            }
        }

        stage('Build Zarf Package') {
            steps {
                container('builder') {
                    sh """
                        apk add --no-cache curl 2>/dev/null || true

                        curl -sSL -o /usr/local/bin/zarf \
                            https://github.com/zarf-dev/zarf/releases/download/${ZARF_VERSION}/zarf-linux-amd64
                        chmod +x /usr/local/bin/zarf

                        cd /workspace
                        mkdir -p packages

                        # Update deployment.yaml image tag to the versioned tag
                        sed -i "s|java-demo-app:latest|java-demo-app:${params.SERVICE_VERSION}|g" \
                            manifests/deployment.yaml

                        zarf package create . \
                            --output ./packages \
                            --architecture amd64 \
                            --set VERSION=${params.SERVICE_VERSION} \
                            --insecure-skip-tls-verify \
                            --confirm

                        ls -lh packages/
                    """
                }
            }
        }

        stage('Upload to RustFS') {
            steps {
                container('builder') {
                    withCredentials([usernamePassword(
                        credentialsId: 'rustfs-credentials',
                        usernameVariable: 'AWS_ACCESS_KEY_ID',
                        passwordVariable: 'AWS_SECRET_ACCESS_KEY'
                    )]) {
                        sh """
                            apk add --no-cache aws-cli 2>/dev/null || true
                            cd /workspace
                            aws s3 cp packages/${PACKAGE_NAME} \
                                s3://${RUSTFS_BUCKET}/${PACKAGE_NAME} \
                                --endpoint-url ${RUSTFS_URL}
                            aws s3 ls s3://${RUSTFS_BUCKET}/ --endpoint-url ${RUSTFS_URL}
                        """
                    }
                }
            }
        }

        stage('Pull from RustFS') {
            steps {
                container('builder') {
                    withCredentials([usernamePassword(
                        credentialsId: 'rustfs-credentials',
                        usernameVariable: 'AWS_ACCESS_KEY_ID',
                        passwordVariable: 'AWS_SECRET_ACCESS_KEY'
                    )]) {
                        sh """
                            mkdir -p /workspace/deploy
                            aws s3 cp \
                                s3://${RUSTFS_BUCKET}/${PACKAGE_NAME} \
                                /workspace/deploy/${PACKAGE_NAME} \
                                --endpoint-url ${RUSTFS_URL}
                        """
                    }
                }
            }
        }

        stage('Deploy via Zarf') {
            steps {
                container('builder') {
                    sh """
                        cd /workspace
                        zarf package deploy deploy/${PACKAGE_NAME} \
                            --insecure-skip-tls-verify \
                            --confirm
                        echo "Deploy complete."
                    """
                }
            }
        }

        stage('Update ArgoCD Repo') {
            when {
                expression { params.UPDATE_ARGOCD }
            }
            steps {
                container('builder') {
                    withCredentials([sshUserPrivateKey(
                        credentialsId: 'github-ssh-key',
                        keyFileVariable: 'SSH_KEY_FILE'
                    )]) {
                        sh """
                            apk add --no-cache openssh-client 2>/dev/null || true

                            mkdir -p /tmp/.ssh
                            cp \$SSH_KEY_FILE /tmp/.ssh/id_rsa
                            chmod 600 /tmp/.ssh/id_rsa
                            ssh-keyscan github.com >> /tmp/.ssh/known_hosts 2>/dev/null
                            export GIT_SSH_COMMAND="ssh -i /tmp/.ssh/id_rsa -o UserKnownHostsFile=/tmp/.ssh/known_hosts"

                            git clone --depth=1 -b ${ARGOCD_REPO_BRANCH} ${ARGOCD_REPO} /tmp/argocd-repo

                            mkdir -p /tmp/argocd-repo/java-demo-app

                            cat > /tmp/argocd-repo/java-demo-app/values.yaml <<EOF
replicaCount: 1
image:
  repository: ${ZARF_REGISTRY}/java-demo-app
  tag: "${params.SERVICE_VERSION}"
service:
  port: 80
  targetPort: 8080
EOF

                            cd /tmp/argocd-repo
                            git config user.email "jenkins@ci"
                            git config user.name "Jenkins CI"
                            git add java-demo-app/values.yaml
                            git diff --cached --quiet && echo "No changes" && exit 0
                            git commit -m "ci: java-demo-app v${params.SERVICE_VERSION} [trivy-passed]"
                            git push origin ${ARGOCD_REPO_BRANCH}
                            echo "ArgoCD repo updated."
                        """
                    }
                }
            }
        }

        stage('Verify') {
            steps {
                container('builder') {
                    sh """
                        apk add --no-cache kubectl 2>/dev/null || true
                        sleep 15
                        kubectl get pods,svc -n java-demo-app || true
                    """
                }
            }
        }
    }

    post {
        success {
            echo "java-demo-app v${params.SERVICE_VERSION} deployed successfully."
        }
        failure {
            echo "Pipeline failed. Check Trivy report in build artifacts."
        }
        always {
            sh "rm -rf /tmp/argocd-repo /tmp/.ssh || true"
        }
    }
}

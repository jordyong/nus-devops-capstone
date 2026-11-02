// FinSecure Pay – transaction-validator CI/CD
// Checkout → Build → Test → Package → Security Checks → Container Build → Image Scan → Push → Deploy → Smoke Test
// Set ROLLBACK_TAG to redeploy an existing image tag (known-good version) without rebuilding.
//
// Security gate: any HIGH or CRITICAL finding from SpotBugs/FindSecBugs (SAST), Trivy (dependencies, secrets,
// IaC misconfiguration) or Trivy (container image) fails the build before the image is pushed or deployed.

// Runs a Trivy scan, keeps the full JSON + HTML report, and returns non-zero if it holds a HIGH/CRITICAL finding.
// Accepted risks (with justification and expiry) live in .trivyignore.yaml.
def trivyGate(String name, String scan) {
    sh """
        trivy ${scan} --quiet --ignorefile .trivyignore.yaml --format json --output reports/trivy-${name}.json
        trivy convert --quiet --format template --template @/usr/local/share/trivy/contrib/html.tpl \
          --output reports/trivy-${name}.html reports/trivy-${name}.json
    """
    return sh(returnStatus: true,
              script: "trivy convert --quiet --ignorefile .trivyignore.yaml --format table --severity HIGH,CRITICAL --exit-code 1 reports/trivy-${name}.json")
}

pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 30, unit: 'MINUTES')
    }

    parameters {
        string(name: 'ROLLBACK_TAG', defaultValue: '',
               description: 'Leave empty for a normal build. Set to an existing image tag (e.g. 1.0.0-6161ce2) to roll back to it.')
    }

    environment {
        IMAGE       = 'jordyong/transaction-validator'
        AWS_REGION  = 'ap-southeast-1'
        CLUSTER     = 'finsecure-eks'
        NAMESPACE   = 'finsecure'
        DEPLOYMENT  = 'transaction-validator'
        KUBECONFIG  = "${WORKSPACE}/.kube/config"
        // Per-build Docker config so registry credentials never land in JENKINS_HOME
        DOCKER_CONFIG = "${WORKSPACE}/.docker"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.GIT_SHORT   = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
                    // 2nd <version> in pom.xml is the project version (the 1st is the Spring Boot parent)
                    env.APP_VERSION = sh(returnStdout: true, script: "sed -n 's:.*<version>\\(.*\\)</version>.*:\\1:p' pom.xml | sed -n 2p").trim()
                    env.IMAGE_TAG   = params.ROLLBACK_TAG ?: "${env.APP_VERSION}-${env.GIT_SHORT}"
                    currentBuild.displayName = "#${env.BUILD_NUMBER} ${params.ROLLBACK_TAG ? 'rollback → ' : ''}${env.IMAGE_TAG}"
                }
            }
        }

        stage('Build') {
            when { expression { !params.ROLLBACK_TAG } }
            steps {
                sh './mvnw -B -ntp clean compile'
            }
        }

        stage('Test') {
            when { expression { !params.ROLLBACK_TAG } }
            steps {
                sh './mvnw -B -ntp test'
            }
            post {
                always {
                    junit 'target/surefire-reports/*.xml'
                }
            }
        }

        stage('Package') {
            when { expression { !params.ROLLBACK_TAG } }
            steps {
                sh './mvnw -B -ntp package -DskipTests'
                archiveArtifacts artifacts: 'target/transaction-validator.jar', fingerprint: true
            }
        }

        stage('Security Checks') {
            when { expression { !params.ROLLBACK_TAG } }
            steps {
                sh 'rm -rf reports && mkdir -p reports'
                script {
                    // Run every scanner so all reports are produced, then fail once with the full list
                    def failures = []
                    if (sh(returnStatus: true, script: './mvnw -B -ntp spotbugs:check') != 0) {
                        failures << 'SAST (SpotBugs + FindSecBugs)'
                    }
                    if (trivyGate('dependencies', 'rootfs --scanners vuln target/transaction-validator.jar') != 0) {
                        failures << 'dependency scan (HIGH/CRITICAL CVEs in the application jar)'
                    }
                    if (trivyGate('repo', 'fs --scanners secret,misconfig --skip-files pom.xml --skip-dirs target --skip-dirs reports .') != 0) {
                        failures << 'secret / IaC misconfiguration scan'
                    }
                    if (failures) {
                        error "Security gate failed: ${failures.join('; ')}"
                    }
                }
            }
            post {
                always {
                    recordIssues enabledForFailure: true, skipPublishingChecks: true, tools: [
                        spotBugs(pattern: 'target/spotbugsXml.xml'),
                        trivy(id: 'trivy-dependencies', name: 'Trivy dependencies', pattern: 'reports/trivy-dependencies.json'),
                        trivy(id: 'trivy-repo', name: 'Trivy secrets + IaC', pattern: 'reports/trivy-repo.json')
                    ]
                    archiveArtifacts artifacts: 'reports/**, target/spotbugsXml.xml', allowEmptyArchive: true
                }
            }
        }

        stage('Container Build') {
            when { expression { !params.ROLLBACK_TAG } }
            steps {
                sh '''
                    docker build \
                      --build-arg VERSION="$APP_VERSION" --build-arg REVISION="$GIT_SHORT" \
                      -t "$IMAGE:$IMAGE_TAG" -t "$IMAGE:$APP_VERSION" -t "$IMAGE:latest" .
                '''
            }
        }

        stage('Image Scan') {
            when { expression { !params.ROLLBACK_TAG } }
            steps {
                script {
                    // Scans the OS packages and the Java libraries inside the image that is about to be pushed
                    if (trivyGate('image', "image --scanners vuln ${env.IMAGE}:${env.IMAGE_TAG}") != 0) {
                        error 'Security gate failed: HIGH/CRITICAL vulnerabilities in the container image – not pushing'
                    }
                }
            }
            post {
                always {
                    recordIssues enabledForFailure: true, skipPublishingChecks: true,
                        tools: [trivy(id: 'trivy-image', name: 'Trivy image', pattern: 'reports/trivy-image.json')]
                    archiveArtifacts artifacts: 'reports/trivy-image.*', allowEmptyArchive: true
                }
            }
        }

        stage('Push') {
            when { expression { !params.ROLLBACK_TAG } }
            steps {
                withCredentials([usernamePassword(credentialsId: 'dockerhub', usernameVariable: 'DH_USER', passwordVariable: 'DH_TOKEN')]) {
                    sh '''
                        echo "$DH_TOKEN" | docker login -u "$DH_USER" --password-stdin
                        docker push "$IMAGE:$IMAGE_TAG"
                        docker push "$IMAGE:$APP_VERSION"
                        docker push "$IMAGE:latest"
                    '''
                }
            }
            post {
                always {
                    sh 'docker logout || true'
                }
            }
        }

        stage('Deploy') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'aws-jenkins', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh '''
                        aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER" --kubeconfig "$KUBECONFIG"

                        # Apply every manifest in k8s/ with the new image tag in a single rollout, so manifest changes
                        # (probes, ports, ConfigMap, Services) ship together with the code that needs them.
                        # The Namespace is cluster-scoped and admin-managed: Jenkins' access entry is limited to it.
                        rm -rf .deploy && mkdir .deploy
                        cat > .deploy/kustomization.yaml <<KUSTOMIZATION
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../k8s
images:
  - name: $IMAGE
    newTag: "$IMAGE_TAG"
patches:
  - patch: |-
      \\$patch: delete
      apiVersion: v1
      kind: Namespace
      metadata:
        name: $NAMESPACE
KUSTOMIZATION
                        kubectl apply -k .deploy
                        kubectl -n "$NAMESPACE" annotate "deploy/$DEPLOYMENT" --overwrite \
                          kubernetes.io/change-cause="Jenkins #$BUILD_NUMBER: $IMAGE:$IMAGE_TAG"

                        # A rollout that never becomes healthy is undone automatically
                        if ! kubectl -n "$NAMESPACE" rollout status "deploy/$DEPLOYMENT" --timeout=180s; then
                          echo "Rollout failed – rolling back to the previous revision"
                          kubectl -n "$NAMESPACE" rollout undo "deploy/$DEPLOYMENT"
                          kubectl -n "$NAMESPACE" rollout status "deploy/$DEPLOYMENT" --timeout=180s
                          exit 1
                        fi

                        kubectl -n "$NAMESPACE" rollout history "deploy/$DEPLOYMENT"
                        kubectl -n "$NAMESPACE" get pods -o wide
                    '''
                }
            }
        }

        stage('Smoke Test') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'aws-jenkins', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    // Calls the Service from inside the cluster, so the check doesn't depend on NLB DNS propagation
                    sh '''
                        kubectl -n "$NAMESPACE" run "smoke-$BUILD_NUMBER" --rm -i --restart=Never --quiet \
                          --image=curlimages/curl:8.11.1 -- \
                          curl -sf --retry 5 --retry-delay 2 "http://$DEPLOYMENT/health"
                    '''
                }
            }
        }
    }

    post {
        success {
            echo "Deployed $IMAGE:$IMAGE_TAG to $CLUSTER/$NAMESPACE"
        }
        failure {
            echo 'Pipeline failed – no new version was deployed (or the failed rollout was undone).'
            emailext to: '$DEFAULT_RECIPIENTS', attachLog: true, compressLog: true,
                subject: "FAILED: ${env.JOB_NAME} ${currentBuild.displayName}",
                body: """<p><b>${env.JOB_NAME} ${currentBuild.displayName} failed.</b> No new version was deployed
                         (or the failed rollout was rolled back).</p>
                         <p>Image: ${env.IMAGE}:${env.IMAGE_TAG}<br>Build: <a href="${env.BUILD_URL}">${env.BUILD_URL}</a></p>
                         <p>Last log lines:</p><pre>\${BUILD_LOG, maxLines=40, escapeHtml=true}</pre>"""
        }
        fixed {
            emailext to: '$DEFAULT_RECIPIENTS',
                subject: "FIXED: ${env.JOB_NAME} ${currentBuild.displayName}",
                body: """<p><b>${env.JOB_NAME} is healthy again.</b> ${currentBuild.displayName} succeeded after a failure
                         and deployed ${env.IMAGE}:${env.IMAGE_TAG} to ${env.CLUSTER}/${env.NAMESPACE}.</p>
                         <p>Build: <a href="${env.BUILD_URL}">${env.BUILD_URL}</a></p>"""
        }
        always {
            sh 'rm -rf "$DOCKER_CONFIG" "$WORKSPACE/.kube" "$WORKSPACE/.deploy"'
        }
    }
}

// FinSecure Pay – transaction-validator CI/CD
// Checkout → Build → Test → Package → Security Checks → Container Build → Push → Deploy → Smoke Test
// Set ROLLBACK_TAG to redeploy an existing image tag (known-good version) without rebuilding.

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
                echo 'Static analysis, dependency and image scanning gates are added in Task 5.'
            }
        }

        stage('Container Build') {
            when { expression { !params.ROLLBACK_TAG } }
            steps {
                sh '''
                    docker build \
                      --build-arg VERSION="$APP_VERSION" --build-arg REVISION="$GIT_SHORT" \
                      -t "$IMAGE:$IMAGE_TAG" -t "$IMAGE:latest" .
                '''
            }
        }

        stage('Push') {
            when { expression { !params.ROLLBACK_TAG } }
            steps {
                withCredentials([usernamePassword(credentialsId: 'dockerhub', usernameVariable: 'DH_USER', passwordVariable: 'DH_TOKEN')]) {
                    sh '''
                        echo "$DH_TOKEN" | docker login -u "$DH_USER" --password-stdin
                        docker push "$IMAGE:$IMAGE_TAG"
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

                        kubectl -n "$NAMESPACE" set image "deploy/$DEPLOYMENT" "transaction-validator=$IMAGE:$IMAGE_TAG"
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
        }
        always {
            sh 'rm -rf "$DOCKER_CONFIG" "$WORKSPACE/.kube"'
        }
    }
}

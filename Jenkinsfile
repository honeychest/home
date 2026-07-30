pipeline {
    agent any

    environment {
        REGISTRY = 'localhost:5010'
    }

    parameters {
        booleanParam(name: 'BACKEND_ONLY',  defaultValue: false, description: 'Backend 강제 배포')
        booleanParam(name: 'FRONTEND_ONLY', defaultValue: false, description: 'Frontend 강제 배포')
        booleanParam(name: 'NEXUS_ONLY',    defaultValue: false, description: 'Nexus 강제 배포')
    }

    stages {
        stage('Sync Local') {
            steps {
                sh 'git -C /Users/honey/devcontext/project/lab pull'
            }
        }

        stage('Detect Changes') {
            steps {
                script {
                    def prev = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: ''
                    // prev 커밋이 현재 히스토리에 실재하는지 검증 (force-push/rebase 대비)
                    def prevOk = prev && sh(
                        script: "git cat-file -e ${prev}^{commit} 2>/dev/null && echo ok || true",
                        returnStdout: true).trim() == 'ok'

                    def range
                    if (prevOk) {
                        range = "${prev} HEAD"              // 지난 성공배포 ~ 현재 전체
                    } else if (sh(script: 'git rev-parse HEAD^2 > /dev/null 2>&1 && echo m || true',
                                  returnStdout: true).trim() == 'm') {
                        range = "HEAD^1 HEAD^2"             // 병합커밋 폴백
                    } else {
                        range = "HEAD~1 HEAD"               // 최초빌드/히스토리없음 폴백
                    }
                    echo "변경 감지 범위: ${range}"

                    def files = sh(script: "git diff --name-only ${range}", returnStdout: true)
                        .trim().split('\n').findAll { it }
                    // .md 등 문서만 바뀐 경우는 배포 대상에서 제외 — 문서 한 줄 고쳐서
                    // 컨테이너 재기동/재배포가 걸리는 일을 만들지 않으려는 것이다.
                    env.DEPLOY_BACK  = files.any { it.startsWith('springboot/')       && !it.endsWith('.md') } ? 'true' : 'false'
                    env.DEPLOY_FRONT = files.any { it.startsWith('frontend/')         && !it.endsWith('.md') } ? 'true' : 'false'
                    env.DEPLOY_NEXUS = files.any { it.startsWith('nexus/')            && !it.endsWith('.md') } ? 'true' : 'false'
                    env.GIT_SHORT    = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                }
            }
        }

        stage('Build & Push Backend') {
            when {
                allOf {
                    branch 'main'
                    anyOf {
                        environment name: 'DEPLOY_BACK', value: 'true'
                        expression { return params.BACKEND_ONLY }
                    }
                }
            }
            steps {
                sh '''
                    cd $WORKSPACE/springboot
                    ./gradlew bootJar --no-daemon
                    docker build -t ${REGISTRY}/chsproject-docker:${GIT_SHORT} .
                    docker tag ${REGISTRY}/chsproject-docker:${GIT_SHORT} ${REGISTRY}/chsproject-docker:latest
                    docker push ${REGISTRY}/chsproject-docker:${GIT_SHORT}
                    docker push ${REGISTRY}/chsproject-docker:latest
                '''
            }
        }

        stage('Deploy Backend') {
            when {
                allOf {
                    branch 'main'
                    anyOf {
                        environment name: 'DEPLOY_BACK', value: 'true'
                        expression { return params.BACKEND_ONLY }
                    }
                }
            }
            steps {
                sh '/Users/honey/devcontext/project/lab/springboot/deploy-back-only.sh'
            }
        }

        stage('Build & Push Frontend') {
            when {
                allOf {
                    branch 'main'
                    anyOf {
                        environment name: 'DEPLOY_FRONT', value: 'true'
                        expression { return params.FRONTEND_ONLY }
                    }
                }
            }
            steps {
                sh '''
                    cd $WORKSPACE/frontend
                    npm ci
                    npm run build
                    docker build -t ${REGISTRY}/chs-frontend:${GIT_SHORT} .
                    docker tag ${REGISTRY}/chs-frontend:${GIT_SHORT} ${REGISTRY}/chs-frontend:latest
                    docker push ${REGISTRY}/chs-frontend:${GIT_SHORT}
                    docker push ${REGISTRY}/chs-frontend:latest
                '''
            }
        }

        stage('Deploy Frontend') {
            when {
                allOf {
                    branch 'main'
                    anyOf {
                        environment name: 'DEPLOY_FRONT', value: 'true'
                        expression { return params.FRONTEND_ONLY }
                    }
                }
            }
            steps {
                sh '/Users/honey/devcontext/project/lab/frontend/deploy-front-only.sh'
            }
        }

        stage('Build & Push Nexus') {
            when {
                allOf {
                    branch 'main'
                    anyOf {
                        environment name: 'DEPLOY_NEXUS', value: 'true'
                        expression { return params.NEXUS_ONLY }
                    }
                }
            }
            steps {
                sh '''
                    docker build -t ${REGISTRY}/chs-nexus:${GIT_SHORT} $WORKSPACE/nexus
                    docker tag ${REGISTRY}/chs-nexus:${GIT_SHORT} ${REGISTRY}/chs-nexus:latest
                    docker push ${REGISTRY}/chs-nexus:${GIT_SHORT}
                    docker push ${REGISTRY}/chs-nexus:latest
                '''
            }
        }

        stage('Deploy Nexus') {
            when {
                allOf {
                    branch 'main'
                    anyOf {
                        environment name: 'DEPLOY_NEXUS', value: 'true'
                        expression { return params.NEXUS_ONLY }
                    }
                }
            }
            steps {
                sh 'cd /Users/honey/devcontext/project/lab/springboot && docker compose pull nexus && docker compose up -d nexus'
            }
        }
    }
}

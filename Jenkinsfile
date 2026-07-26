pipeline {
    agent any

    environment {
        REGISTRY = 'localhost:5010'
    }

    parameters {
        booleanParam(name: 'BACKEND_ONLY',  defaultValue: false, description: 'Backend 강제 배포')
        booleanParam(name: 'FRONTEND_ONLY', defaultValue: false, description: 'Frontend 강제 배포')
        booleanParam(name: 'NEXUS_ONLY',    defaultValue: false, description: 'Nexus 강제 배포')
        booleanParam(name: 'GIKKA_ONLY',    defaultValue: false, description: 'gikka 로컬 추출기 강제 재기동')
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

                    def changed = sh(script: "git diff --name-only ${range}", returnStdout: true).trim()
                    env.DEPLOY_BACK  = changed.contains('springboot/') ? 'true' : 'false'
                    env.DEPLOY_FRONT = changed.contains('frontend/')   ? 'true' : 'false'
                    env.DEPLOY_NEXUS = changed.contains('nexus/')      ? 'true' : 'false'
                    // DEPLOY_GIKKA 는 mac-mini 호스트 추출기(launchd)다 — recipe 백엔드 앱이 아니다.
                    // 백엔드 앱(기까)은 2026-07-26 에 honeychest/gikka 저장소로 분리돼 나갔고
                    // 자기 Jenkinsfile 로 배포한다. 이 파이프라인은 그것을 알지 못한다.
                    env.DEPLOY_GIKKA = changed.contains('gikka-extractor/') ? 'true' : 'false'
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

        // gikka 로컬 추출기(mac-mini 호스트 상시 프로세스)는 도커가 아니라 launchd 가 띄운다.
        // launchd 가 체크아웃의 server.py 를 직접 돌리므로(사본 없음 — plist 주석 참고)
        // Sync Local 의 git pull 이면 파일은 이미 최신이고, 재기동만 하면 반영된다.
        // 이 stage 가 없던 동안 gikka-extractor/ 변경은 아무리 푸시해도 반영되지 않았다 (2026-07-16 발견).
        // (폴더명 gikka/ → gikka-extractor/ 로 변경 — recipe 백엔드 분리로 gikka/ 는 새 Spring Boot
        // 서비스 자리가 됨. mac-mini 의 plist 실행 경로도 이 이름으로 함께 갱신 필요.)
        //
        // 주의 (2026-07-16 실측): Jenkins 는 push 웹훅을 받으면 그 시점 브랜치의 Jenkinsfile 로
        // 파이프라인을 정의한 뒤 Sync Local(git pull)을 돈다. 그래서 stage 정의 자체가 처음
        // 들어오는 커밋(예: 이 stage 를 신설한 recipe - 24)은 옛 정의로 실행돼 한 박자 늦는다 —
        // 그 커밋만 수동 재기동이 필요하고, 이후 gikka-extractor/ 변경부터는 자동으로 잡힌다.
        stage('Deploy Gikka Local') {
            when {
                allOf {
                    branch 'main'
                    anyOf {
                        environment name: 'DEPLOY_GIKKA', value: 'true'
                        expression { return params.GIKKA_ONLY }
                    }
                }
            }
            steps {
                sh 'launchctl kickstart -k gui/$(id -u)/com.gikka.local-extractor'
            }
        }
    }
}

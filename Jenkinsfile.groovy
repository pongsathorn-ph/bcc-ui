def replaceTemplate(String fileName, String outputPath, Map replacementMap) {
  def content = readFile("${env.HELM_TEMPLATE_DIR}/${fileName}")
  replacementMap.each { key, value -> content = content.replace(key, value) }
  writeFile file: outputPath, text: content
}

def replaceChart(String version) {
  replaceTemplate("Chart.yaml", "${env.HELM_CHART_DIR}/Chart.yaml", ["{{CHART_VERSION}}": "${version}"])
}

def replaceValue() {
  replaceTemplate("values.yaml", "${env.HELM_CHART_DIR}/values/values-dev.yaml", ["{{IMAGE_REPO}}": "${env.IMAGE_REPO_DEV}"])
  if (params.buildType == 'RELEASE TAG') {
    replaceTemplate("values.yaml", "${env.HELM_CHART_DIR}/values/values-pre.yaml", ["{{IMAGE_REPO}}": "${env.IMAGE_REPO_PRE}"])
    replaceTemplate("values.yaml", "${env.HELM_CHART_DIR}/values/values-pro.yaml", ["{{IMAGE_REPO}}": "${env.IMAGE_REPO_PRO}"])
  }
}

def replaceDeployment() {
  if (params.buildType == 'RELEASE TAG') {
    replaceTemplate("deployment.yaml", "${env.HELM_CHART_DIR}/templates/deployment.yaml", ["{{IMAGE_REPO}}": "{{.Values.image.repository}}"]).yaml
  } else {
    replaceTemplate("deployment.yaml", "${env.HELM_CHART_DIR}/templates/deployment.yaml", ["{{IMAGE_REPO}}": "${env.imageRepoDev}"])
  }
}

def gitCheckout(String tagName) {
  try {
    echo 'Checkout - Starting.'
    cleanWs()
    checkout([$class: 'GitSCM', branches: [[name: "${tagName}"]], extensions: [], userRemoteConfigs: [[credentialsId: "${env.GITHUB_CREDENTIAL_ID}", url: "${env.GIT_REPO_URL}"]]])
    echo 'Checkout - Completed.'
  } catch (err) {
    echo 'Checkout - Failed.'
    currentBuild.result = 'FAILURE'
    error(err.message)
  }
}

def getChartVersion() {
  def yaml = readYaml file: "${env.HELM_TEMPLATE_DIR}/deployment.yaml"
  def version = yaml.spec.template.spec.containers[0].image.split(':')[1]
  env.CHART_VERSION = version
}

pipeline {
  agent any

  parameters {
    string(name: 'chartVersion', defaultValue: params.chartVersion, description: 'Please fill version.')
    choice(name: 'buildType', choices: ['ALPHA','RELEASE TAG'], description: 'Please select build type.')
  }

  environment {
    BUILD_NUMBER = String.format("%04d", currentBuild.number)
    CURR_DIR = sh(script: 'sudo pwd', returnStdout: true).trim()
    HELM_CHART_DIR = "${env.CURR_DIR}/helm-chart"
    HELM_TEMPLATE_DIR = "${env.CURR_DIR}/helm-template"

    GITHUB_CREDENTIAL_ID = "GITHUB-jenkins"
    GITHUB_CREDENTIAL = credentials("${GITHUB_CREDENTIAL_ID}")
    GIT_BRANCH_NAME = "main"
    GIT_REPO_URL = "https://github.com/pongsathorn-ph/bcc-ui.git"

    CHART_NAME = "bcc-ui-chart"
    CHART_VERSION = "${params.chartVersion}-${env.BUILD_NUMBER}-${params.buildType}"

    IMAGE_REPO_DEV = "pongsathorn/demo-ui-dev"
    IMAGE_REPO_PRE = "pongsathorn/demo-ui-pre"
    IMAGE_REPO_PRO = "pongsathorn/demo-ui-pro"

    TAG_NAME_PRE_ALPHA = "${params.chartVersion}-PRE-ALPHA"
    TAG_NAME_ALPHA = "${params.chartVersion}-ALPHA"
  }

  stages {
    stage('Initial') {
      when {
        expression {
          params.buildType != 'initial'
        }
      }
      steps {
        script {
          currentBuild.displayName = "${params.chartVersion}-${env.BUILD_NUMBER}"

          if (params.buildType == 'RELEASE TAG') {
            env.chartVersion = currentBuild.displayName
          }
        }
      }
    }

    stage('Build Alpha') {
      when {
        expression {
          buildType == 'ALPHA'
        }
      }
      stages {
        stage('Checkout') {
          steps {
            script {
              currentBuild.displayName = "${currentBuild.displayName} : ALPHA"
              gitCheckout("${env.GIT_BRANCH_NAME}") // FIXME จะต้อง checkout จาก PRE_ALPHA
            }
          }
        }

        stage("Replace") {
          steps {
            script {
              try {
                echo "Replace - Starting."
                sh "sudo mkdir -p ${env.HELM_CHART_DIR}/assets"
                replaceChart("${env.CHART_VERSION}")
                replaceValue()
                replaceDeployment()
                sh "sudo cp ${env.HELM_TEMPLATE_DIR}/service.yaml ${env.HELM_CHART_DIR}/templates"
                sh "sudo ls -al ${env.HELM_CHART_DIR}"
                echo "Replace - Completed."
              } catch(err) {
                echo "Replace - Failed."
                currentBuild.result = 'FAILURE'
                error(err.message)
              }
            }
          }
        }

        stage("Package") {
          steps {
            script {
              try {
                echo "Package - Starting."
                sh """
                  sudo mkdir -p ${env.HELM_CHART_DIR}/assets
                  sudo helm package ${env.HELM_CHART_DIR} -d ${env.HELM_CHART_DIR}/temp
                  sudo helm repo index --url assets --merge ${env.HELM_CHART_DIR}/index.yaml ${env.HELM_CHART_DIR}/temp
                  ls ${env.HELM_CHART_DIR}/temp
                  sudo mv ${env.HELM_CHART_DIR}/temp/${env.CHART_NAME}-*.tgz ${env.HELM_CHART_DIR}/assets
                  sudo mv ${env.HELM_CHART_DIR}/temp/index.yaml ${env.HELM_CHART_DIR}/
                  sudo rm -rf ${env.HELM_CHART_DIR}/temp
                """
                echo "Package - Completed."
              } catch (err) {
                echo "Package - Failed."
                currentBuild.result = 'FAILURE'
                error('Package stage failed.')
              }
            }
          }
        }

        stage('Git commit and push') {
          steps {
            script {
              try {
                echo 'GIT Commit - Starting.'
                sh """
                  git config --global user.name 'Jenkins Pipeline'
                  git config --global user.email 'jenkins@localhost'
                  git checkout -b ${env.GIT_BRANCH_NAME}
                  git add .
                  git commit -m 'Update from Jenkins-Pipeline'
                  git push https://$GITHUB_CREDENTIAL_USR:$GITHUB_CREDENTIAL_PSW@github.com/pongsathorn-ph/png-iapi-chart.git ${env.GIT_BRANCH_NAME}
                """
                echo 'GIT Commit - Completed.'
              } catch (err) {
                echo 'GIT Commit - Failed.'
                currentBuild.result = 'FAILURE'
                error(err.message)
              }
            }
          }
        }

        stage('Remove tag') {
          steps {
            script {
              echo 'Remove tag - Starting.'
              catchError(buildResult: 'SUCCESS',stageResult: 'SUCCESS') {
                sh """
                  git tag -d ${env.TAG_NAME_ALPHA}
                  git push --delete https://$GITHUB_CREDENTIAL_USR:$GITHUB_CREDENTIAL_PSW@github.com/pongsathorn-ph/png-iapi-chart.git ${env.TAG_NAME_ALPHA}
                """
              }
              echo 'Remove tag - Completed.'
            }
          }
        }

        stage('Push tag') {
          steps {
            script {
              try {
                echo 'Push tag - Starting.'
                sh """
                  git tag ${env.TAG_NAME_ALPHA}
                  git push https://$GITHUB_CREDENTIAL_USR:$GITHUB_CREDENTIAL_PSW@github.com/pongsathorn-ph/png-iapi-chart.git ${env.TAG_NAME_ALPHA}
                """
                echo 'Push tag - Completed.'
              } catch (err) {
                echo 'Push tag - Failed.'
                currentBuild.result = 'FAILURE'
                error(err.message)
              }
            }
          }
        }
      }
    }

    // stage('Build Tag') {
    //   when {
    //     expression {
    //       params.buildType == 'RELEASE TAG'// && params.chartVersion == env.tagVersion
    //     }
    //   }
    //   stages {
    //     stage('Checkout') {
    //       steps {
    //         script {
    //           currentBuild.displayName = "${currentBuild.displayName} : TAG 🏷️"
    //           checkout("refs/tags/${env.TAG_NAME_ALPHA}")
    //         }
    //       }
    //     }

    //     state('Replace') {
    //       steps {
    //         script {
    //           replaceChart()
    //         }
    //       }
    //     }

    //     stage('Push tag') {
    //       steps {
    //         script {
    //           try {
    //             echo 'Push tag - Starting.'
    //             sh """
    //               git tag ${params.chartVersion}
    //               git push https://$GITHUB_CREDENTIAL_USR:$GITHUB_CREDENTIAL_PSW@github.com/pongsathorn-ph/png-iapi-chart.git ${params.chartVersion}
    //             """
    //             echo 'Push tag - Completed.'
    //           } catch (err) {
    //             echo 'Push tag - Failed.'
    //             currentBuild.result = 'FAILURE'
    //             error(err.message)
    //           }
    //         }
    //       }

    //       post {
    //         success {
    //           script {
    //             if (currentBuild.result == "SUCCESS") {
    //               catchError(buildResult: 'SUCCESS', stageResult: 'SUCCESS') {
    //                 sh """
    //                   git tag -d ${env.TAG_NAME_ALPHA}
    //                   git push --delete https://$GITHUB_CREDENTIAL_USR:$GITHUB_CREDENTIAL_PSW@github.com/pongsathorn-ph/png-iapi-chart.git ${env.TAG_NAME_ALPHA}
    //                 """
    //               }
    //             }
    //           }
    //         }
    //       }
    //     }
    //   }
    // }
  }
}

def replaceTemplate(String fileName, String outputPath, Map replacementMap) {
  def content = readFile("${env.HELM_TEMPLATE_DIR}/${fileName}")
  replacementMap.each { key, value -> content = content.replace(key, value) }
  writeFile file: outputPath, text: content
}

def getChartName() {
  def yaml = readYaml file: "${env.HELM_TEMPLATE_DIR}/Chart.yaml"
  def data = yaml.name
  return data
}

def packageProcess() {
  sh """
    sudo mkdir -p ${env.HELM_CHART_DIR}/assets
    sudo helm package ${env.HELM_CHART_DIR} -d ${env.HELM_CHART_DIR}/temp
    sudo helm repo index --url assets --merge ${env.HELM_CHART_DIR}/index.yaml ${env.HELM_CHART_DIR}/temp

    sudo mv ${env.HELM_CHART_DIR}/temp/${env.CHART_NAME}-*.tgz ${env.HELM_CHART_DIR}/assets
    sudo mv ${env.HELM_CHART_DIR}/temp/index.yaml ${env.HELM_CHART_DIR}/
    sudo rm -rf ${env.HELM_CHART_DIR}/temp
  """
}

def gitCheckoutProcess(String tagName) {
  cleanWs()
  checkout([$class: 'GitSCM', branches: [[name: tagName]], extensions: [], userRemoteConfigs: [[credentialsId: env.GITHUB_CREDENTIAL_ID, url: "https://${env.GIT_REPO}"]]])
}

def gitCommitPushProcess() {
  withCredentials([gitUsernamePassword(credentialsId: "${env.GITHUB_CREDENTIAL_ID}", gitToolName: 'Default')]) {
    sh """
      git config --global user.name 'Jenkins Pipeline'
      git config --global user.email 'jenkins@localhost'
      git checkout -b ${env.GIT_BRANCH_NAME}
      git add .
      git commit -m 'Update from Jenkins-Pipeline'
      git push origin ${env.GIT_BRANCH_NAME}
    """
  }
}

def gitRemoveTagProcess(String tagName) {
  catchError(buildResult: 'SUCCESS', stageResult: 'SUCCESS') {
    sh """
      git tag -d ${tagName}
      git push --delete https://$GITHUB_CREDENTIAL_USR:$GITHUB_CREDENTIAL_PSW@${env.GIT_REPO} ${tagName}
    """
  }
}

def gitPushTagProcess(String tagName) {
  sh """
    git tag ${tagName}
    git push https://$GITHUB_CREDENTIAL_USR:$GITHUB_CREDENTIAL_PSW@${env.GIT_REPO} ${tagName}
  """
}

pipeline {
  agent any

  parameters {
    string(name: 'chartVersion', defaultValue: params.chartVersion, description: 'Please fill version.')
    choice(name: 'buildType', choices: ['ALPHA','RELEASE TAG'], description: 'Please select build type.')
  }

  environment {
    BUILD_NUMBER = String.format("%04d", currentBuild.number)
    HELM_CHART_DIR = "${env.WORKSPACE}/helm-chart"
    HELM_TEMPLATE_DIR = "${env.WORKSPACE}/helm-template"

    GITHUB_CREDENTIAL_ID = "GITHUB-jenkins"
    GITHUB_CREDENTIAL = credentials("${GITHUB_CREDENTIAL_ID}")

    GIT_BRANCH_NAME = "main"
    GIT_REPO = "github.com/pongsathorn-ph/bcc-ui.git"

    CHART_NAME = "bcc-ui-chart"
    CHART_VERSION = "${params.chartVersion}-${env.BUILD_NUMBER}-${params.buildType}"

    IMAGE_REPO_PRE = "pongsathorn/demo-ui-pre"
    IMAGE_REPO_ALPHA = "pongsathorn/demo-ui-dev"
    IMAGE_REPO_PRO = "pongsathorn/demo-ui-pro"

    TAG_NAME_PRE = "${params.chartVersion}-PRE-ALPHA"
    TAG_NAME_ALPHA = "${params.chartVersion}-ALPHA"
    TAG_NAME_PRO = "${params.chartVersion}"
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
        stage('Preparing') {
          steps {
            script {
              currentBuild.displayName = "${currentBuild.displayName} : ALPHA"
            }
          }
        }

        stage('Checkout') {
          steps {
            script {
              try {
                echo 'Checkout - Starting.'
                gitCheckoutProcess("${env.GIT_BRANCH_NAME}") // FIXME จะต้อง checkout จาก PRE_ALPHA
                echo 'Checkout - Completed.'
              } catch (err) {
                echo 'Checkout - Failed.'
                currentBuild.result = 'FAILURE'
                error(err.message)
              }
            }
          }
        }

        stage("Replacement") {
          steps {
            script {
              try {
                echo "Replace - Starting."
                sh "sudo mkdir -p ${env.HELM_CHART_DIR}/assets"

                replaceTemplate("Chart.yaml", "${env.HELM_CHART_DIR}/Chart.yaml", ["{{CHART_VERSION}}": "${env.CHART_VERSION}"])
                replaceTemplate("values.yaml", "${env.HELM_CHART_DIR}/values/values-dev.yaml", ["{{IMAGE_REPO}}": "${env.IMAGE_REPO_ALPHA}"])
                replaceTemplate("deployment.yaml", "${env.HELM_CHART_DIR}/templates/deployment.yaml", ["{{IMAGE_REPO}}": "${env.IMAGE_REPO_ALPHA}"])

                sh "sudo cp ${env.HELM_TEMPLATE_DIR}/service.yaml ${env.HELM_CHART_DIR}/templates"
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
                packageProcess()
                echo "Package - Completed."
              } catch (err) {
                echo "Package - Failed."
                currentBuild.result = 'FAILURE'
                error('Package stage failed.')
              }
            }
          }
        }

        stage('Commit and Push') {
          steps {
            script {
              try {
                echo 'GIT Commit - Starting.'
                gitCommitPushProcess()
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
              gitRemoveTagProcess("${env.TAG_NAME_ALPHA}")
              echo 'Remove tag - Completed.'
            }
          }
        }

        stage('Push tag') {
          steps {
            script {
              try {
                echo 'Push tag - Starting.'
                gitPushTagProcess("${env.TAG_NAME_ALPHA}")
                echo 'Push tag - Completed.'
              } catch (err) {
                echo 'Push tag - Failed.'
                currentBuild.result = 'FAILURE'
                error(err.message)
              }
            }
          }
        }

        // เพิ่ม stage เรียก Job ของ PNG-IAPI_WEB
        // stage('Call PNG-IAPI_WEB') {
        //
        // }
      }
    }

    stage('Build Tag') {
      when {
        expression {
          params.buildType == 'RELEASE TAG'// && params.chartVersion == env.tagVersion
        }
      }
      stages {
        stage('Preparing') {
          steps {
            script {
              currentBuild.displayName = "${currentBuild.displayName} : TAG 🏷️"
            }
          }
        }

        stage('Checkout') {
          steps {
            script {
              try {
                echo 'Checkout - Starting.'
                gitCheckoutProcess("refs/tags/${env.TAG_NAME_ALPHA}")
                echo 'Checkout - Completed.'
              } catch (err) {
                echo 'Checkout - Failed.'
                currentBuild.result = 'FAILURE'
                error(err.message)
              }
            }
          }
        }

        stage('Remove ALPHA from index') {
          steps {
            script {
              try {
                echo "Remove ALPHA from index - Starting."

                def yaml = readYaml file: "${env.HELM_CHART_DIR}/index.yaml"
                def chartEntries = yaml.entries["${env.CHART_NAME}"]

                int index = 0
                while (index < chartEntries.size()) {
                  if (chartEntries[index]['version'].contains('ALPHA')) {
                    yaml.entries["${env.CHART_NAME}"].remove(index)
                  } else {
                    index++
                  }
                }

                writeYaml file: "${env.HELM_CHART_DIR}/index.yaml", data: yaml, overwrite: true
                echo "Remove ALPHA from index - Completed."
              } catch(err) {
                echo "Remove ALPHA from index - Failed."
                currentBuild.result = 'FAILURE'
                error(err)
              }
            }
          }
        }

        stage('Remove ALPHA from assets') {
          steps {
            script {
              try {
                echo "Remove ALPHA from assets - Starting."
                sh "rm -f ${env.HELM_CHART_DIR}/assets/*-ALPHA.tgz"
                echo "Remove ALPHA from assets - Completed."
              } catch(err) {
                echo "Remove ALPHA from assets - Failed."
                currentBuild.result = 'FAILURE'
                error(err)
              }
            }
          }
        }

        stage('Replacement') {
          steps {
            script {
              try {
                echo "Replace - Starting."
                replaceTemplate("Chart.yaml", "${env.HELM_CHART_DIR}/Chart.yaml", ["{{CHART_VERSION}}": "${params.chartVersion}"])
                replaceTemplate("values.yaml", "${env.HELM_CHART_DIR}/values/values-pre.yaml", ["{{IMAGE_REPO}}": "${env.IMAGE_REPO_PRE}"])
                replaceTemplate("values.yaml", "${env.HELM_CHART_DIR}/values/values-pro.yaml", ["{{IMAGE_REPO}}": "${env.IMAGE_REPO_PRO}"])
                replaceTemplate("deployment.yaml", "${env.HELM_CHART_DIR}/templates/deployment.yaml", ["{{IMAGE_REPO}}": "{{.Values.image.repository}}"])
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
                packageProcess()
                echo "Package - Completed."
              } catch (err) {
                echo "Package - Failed."
                currentBuild.result = 'FAILURE'
                error('Package stage failed.')
              }
            }
          }
        }

        stage('Commit and Push') {
          steps {
            script {
              try {
                echo 'GIT Commit - Starting.'
                gitCommitPushProcess()
                echo 'GIT Commit - Completed.'
              } catch (err) {
                echo 'GIT Commit - Failed.'
                currentBuild.result = 'FAILURE'
                error(err.message)
              }
            }
          }
        }

        stage('Push tag') {
          steps {
            script {
              try {
                echo 'Push tag - Starting.'
                gitPushTagProcess("${env.TAG_NAME_PRO}")
                echo 'Push tag - Completed.'
              } catch (err) {
                echo 'Push tag - Failed.'
                currentBuild.result = 'FAILURE'
                error(err.message)
              }
            }
          }

          post {
            success {
              script {
                if (currentBuild.result == "SUCCESS") {
                  gitRemoveTagProcess("${env.TAG_NAME_PRE_ALPHA}")
                  gitRemoveTagProcess("${env.TAG_NAME_ALPHA}")
                }
              }
            }
          }
        }
      }
    }
  }
}

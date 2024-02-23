// METHODS FOR REPLACEMENT
def replaceTemplate(String fileName, String outputPath, Map replacementMap) {
  def content = readFile("${env.helmTemplateDir}/${fileName}")
  replacementMap.each { key, value -> content = content.replace(key, value) }
  writeFile file: outputPath, text: content
}

def replaceChart() {
  replaceTemplate("Chart.yaml", "${env.helmChartDir}/Chart.yaml", ["{{CHART_VERSION}}": "${env.chartVersion}"])
}

def replaceValue() {
  replaceTemplate("values.yaml", "${env.helmChartDir}/values/values-dev.yaml", ["{{IMAGE_REPO}}": "${env.imageRepoDev}"])
  if (params.releaseTag) {
    replaceTemplate("values.yaml", "${env.helmChartDir}/values/values-pre.yaml", ["{{IMAGE_REPO}}": "${env.imageRepoPre}"])
    replaceTemplate("values.yaml", "${env.helmChartDir}/values/values-pro.yaml", ["{{IMAGE_REPO}}": "${env.imageRepoPro}"])
  }
}

def replaceDeployment() {
  if (params.releaseTag) {
    replaceTemplate("deployment.yaml", "${env.helmChartDir}/templates/deployment.yaml", ["{{IMAGE_REPO}}": "{{.Values.image.repository}}"]).yaml
  } else {
    replaceTemplate("deployment.yaml", "${env.helmChartDir}/templates/deployment.yaml", ["{{IMAGE_REPO}}": "${env.imageRepoDev}"])
  }
}

// METHODS FOR HELM
def helmUpgrade() {
  sh """
    sudo helm repo update ${env.chartRepoName}
    sudo helm upgrade --install cp-ui ${env.chartRepoName}/${env.chartName} --namespace ${params.namespace} --create-namespace --version ${env.chartVersion} --kubeconfig ${env.kubeConfigDir} --debug --atomic --timeout 2m0s
  """
}

pipeline {
  agent any

  parameters {
    string(name: 'namespace', defaultValue: params.namespace, description: 'Please fill namespace.')
    string(name: 'chartVersion', defaultValue: params.chartVersion, description: 'Please fill version.')
    choice(name: 'buildType', choices: ['alpha'], description: 'Please select build type.')
    booleanParam(name: 'releaseTag', description: '')
  }

  environment {
    currentDir = sh(script: 'sudo pwd', returnStdout: true).trim()
    helmChartDir = "${env.currentDir}/helm-chart"
    helmTemplateDir = "${env.currentDir}/helm-template"
    kubeConfigDir = "/root/.kube/Config"

    gitCredentialId = "GITHUB-jenkins"
    gitBranch = "main"
    gitRepoUrl = "https://github.com/pongsathorn-ph/cp-ui.git"

    chartRepoName = "demo-repo"
    chartRepoUrl = "https://pongsathorn-ph.github.io/cp-ui/helm-chart/"
    chartName = "cp-ui-chart"
    currentBuild = String.format("%04d", currentBuild.number)
    chartVersion = "${params.chartVersion}-${env.currentBuild}-${params.buildType}"

    imageRepoDev = "pongsathorn/demo-ui-dev"
    imageRepoPre = "pongsathorn/demo-ui-pre"
    imageRepoPro = "pongsathorn/demo-ui-pro"
  }

  stages {

    stage("Initial") {
      steps {
        script {
          if (params.releaseTag) {
            withEnv(["chartVersion=${params.chartVersion}-${env.currentBuild}"]) {
              echo "Release tag: ${params.releaseTag}"
              echo "Chart version: ${env.chartVersion}"
            }
          }
        }
      }
    }

    stage("Checkout") {
      steps {
        script {
          try {
            echo "Checkout - Starting."
            cleanWs()
            checkout([$class: 'GitSCM', branches: [[name: "${env.gitBranch}"]], extensions: [], userRemoteConfigs: [[credentialsId: "${env.gitCredentialId}", url: "${env.gitRepoUrl}"]]])
            echo "Checkout - Completed."
          } catch (err) {
            echo "Checkout - Failed."
            currentBuild.result = 'FAILURE'
            error('Checkout stage failed.')
          }
        }
      }
    }

    stage("Replace") {
      steps {
        script {
          try {
            echo "Replace - Starting."
            sh "sudo mkdir -p ${env.helmChartDir}/assets"
            replaceChart()
            replaceValue()
            replaceDeployment()
            sh "sudo cp ${env.helmTemplateDir}/service.yaml ${env.helmChartDir}/templates"
            sh "sudo ls -al ${env.helmChartDir}"
            echo "Replace - Completed."
          } catch(err) {
            echo "Replace - Failed."
            currentBuild.result = 'FAILURE'
            error('Package stage failed.')
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
              sudo mkdir -p ${env.helmChartDir}/assets
              sudo helm package ${env.helmChartDir} -d ${env.helmChartDir}/temp
              sudo helm repo index --url assets --merge ${env.helmChartDir}/index.yaml ${env.helmChartDir}/temp
              ls ${env.helmChartDir}/temp
              sudo mv ${env.helmChartDir}/temp/${env.chartName}-*.tgz ${env.helmChartDir}/assets
              sudo mv ${env.helmChartDir}/temp/index.yaml ${env.helmChartDir}/
              sudo rm -rf ${env.helmChartDir}/temp
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

    stage("Git commit and push") {
      steps {
        script {
          try {
            sh """
              git config --global user.name 'Jenkins Pipeline'
              git config --global user.email 'jenkins@localhost'
              git checkout -b ${env.gitBranch}
              git add .
              git commit -m 'Update from Jenkins-Pipeline'
            """
            withCredentials([gitUsernamePassword(credentialsId: "${env.gitCredentialId}", gitToolName: 'Default')]) {
              sh "git push origin ${env.gitBranch}"
            }
          } catch(err) {
            echo "GIT - Failed."
            currentBuild.result = 'FAILURE'
            error('Git stage failed.')
          }
        }
      }
    }
/*
    stage("Helm install") {
      steps {
        script {
          try {
            sleep 60
            sh "sudo helm repo add ${env.chartRepoName} ${env.chartRepoUrl}"
            helmUpgrade()
          } catch (err) {
            retry(2) {
              sleep 60
              helmUpgrade()
            }
          }
        }
      }
    }
*/
  }
}

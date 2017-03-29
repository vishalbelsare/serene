#!groovy

node {
  def sbtHome = tool 'default-sbt'

  def SBT = "${sbtHome}/bin/sbt -Dsbt.log.noformat=true"

  def branch = env.BRANCH_NAME

  echo "current branch is ${branch}"

  checkout scm

  stage('Cleanup') {
    sh "${SBT} clean"
    echo "in cleanup"
  }

  stage('Build') {
    sh "${SBT} compile"
    echo "in build"
  }

  stage('Test') {
    sh "${SBT} serene-core/test || true"
    echo "serene-core test done"
  }

  step([$class: 'JUnitResultArchiver', testResults: '**/core/target/test-reports/*.xml'])
}
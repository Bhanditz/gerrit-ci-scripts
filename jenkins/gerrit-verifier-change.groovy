// Copyright (C) 2015 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import hudson.model.*
import hudson.AbortException
import hudson.console.HyperlinkNote
import java.util.concurrent.CancellationException
import groovy.json.*
import java.text.*

verbose = true

String.metaClass.encodeURL = {
  java.net.URLEncoder.encode(delegate)
}

class Globals {
  static String gerrit = "https://gerrit-review.googlesource.com/"
  static long curlTimeout = 10000
  static int waitForResultTimeout = 10000
  static Map buildsList = [:]

  static def ciTag(String operation) {
    " \"tag\" : \"autogenerated:gerrit-ci:$operation\" "
  }

  static String addVerifiedTag = ciTag("addVerified")
  static Set<String> codeStyleBranches = ["master", "stable-2.14", "stable-2.15"]
  static resTicks = [ 'ABORTED':'\u26aa', 'SUCCESS':'\u2705', 'FAILURE':'\u274c' ]
}


def gerritPost(url, jsonPayload) {
  def error = ""
  def gerritPostUrl = Globals.gerrit + url
  def curl = ['curl',
  '-n', '-s', '-S',
  '-X', 'POST', '-H', 'Content-Type: application/json',
  '--data-binary', jsonPayload,
    gerritPostUrl ]
  if(verbose) { println "CURL/EXEC> $curl" }
  def proc = curl.execute()
  def sout = new StringBuffer(), serr = new StringBuffer()
  proc.consumeProcessOutput(sout, serr)
  proc.waitForOrKill(Globals.curlTimeout)
  def curlExit = proc.exitValue()
  if(curlExit != 0) {
    error = "$curl **FAILED** with exit code = $curlExit"
    println error
    throw new IOException(error)
  }

  if(!sout.toString().trim().isEmpty() && verbose) {
    println "CURL/OUTPUT> $sout"
  }
  if(!serr.toString().trim().isEmpty() && verbose) {
    println "CURL/ERROR> $serr"
  }

  return 0
}

def gerritLabelVerify(change, sha1, verified, builds) {
  if(verified == 0) {
    return;
  }

  def changeNum = change._number

  def msgList = builds.collect { type,build ->
    [ 'type': type, 'res': build.getResult().toString(), 'url': build.getBuildUrl() + "consoleText" ]
  } sort { a,b -> a['res'].compareTo(b['res']) }

  def msgBody = msgList.collect {
    "${Globals.resTicks[it.res]} ${it.type} : ${it.res}\n    (${it.url})"
  } .join('\n')

  def addVerifiedExit = gerritLabel(changeNum, sha1, 'Verified', verified, msgBody)
  if(addVerifiedExit == 0) {
    println "----------------------------------------------------------------------------"
    println "Gerrit Review: Verified=" + verified + " to change " + changeNum + "/" + sha1
    println "----------------------------------------------------------------------------"
  }

  return addVerifiedExit
}

def findCodestyleFilesInLog(build) {
  def codestyleFiles = []
  def needsFormatting = false
  def codestyleLogReader = build.getLogReader()
  codestyleLogReader.eachLine {
    needsFormatting = needsFormatting || (it ==~ /.*Need Formatting.*/)
    if(needsFormatting && it ==~ /\[.*\]/) {
      codestyleFiles += it.substring(1,it.length()-1)
    }
  }

  return codestyleFiles
}

def gerritLabelCodestyle(change, sha1, cs, files, build) {
  if(cs == 0) {
    return
  }

  def changeNum = change._number
  def formattingMsg = cs < 0 ? ('The following files need formatting:\n    ' + files.join('\n    ')) : 'All files are correctly formatted'
  def res = build.getResult().toString()
  def url = build.getBuildUrl() + "consoleText"

  def msgBody = "${Globals.resTicks[res]} $formattingMsg\n    (${url})"

  def addCodeStyleExit = gerritLabel(changeNum, sha1, 'Code-Style', cs, msgBody)
  if(addCodeStyleExit == 0) {
    println "----------------------------------------------------------------------------"
    println "Gerrit Review: Code-Style=" + cs + " to change " + changeNum + "/" + sha1
    println "----------------------------------------------------------------------------"
  }

  return addCodeStyleExit
}

def gerritLabel(changeNum, sha1, label, score, msgBody = "") {
  def notify = score < 0 ? ', "notify" : "OWNER"' : ''
  def jsonPayload = '{"labels":{"' + label + '":' + score + '},' +
                    ' "message": "' + msgBody + '"' +
                    notify + ", ${Globals.addVerifiedTag} }"

  return gerritPost("a/changes/" + changeNum + "/revisions/" + sha1 + "/review",
                    jsonPayload)
}

def waitForResult(b) {
  def res = null
  def startWait = System.currentTimeMillis()
  while(res == null && (System.currentTimeMillis() - startWait) < Globals.waitForResultTimeout) {
    res = b.getResult()
    if(res == null) {
      Thread.sleep(100) {
      }
    }
  }
  return res == null ? Result.FAILURE : res
}

def getVerified(acc, res) {
  if(res == null || res == Result.ABORTED) {
    return 0
  }

  switch(acc) {
        case 0: return 0
        case 1:
          if(res == null) {
            return 0;
          }
          switch(res) {
            case Result.SUCCESS: return +1;
            case Result.FAILURE: return -1;
            default: return 0;
          }
        case -1: return -1
  }
}

def getChangedFiles(changeNum, sha1) {
  URL filesUrl = new URL(String.format("%schanges/%s/revisions/%s/files/",
      Globals.gerrit, changeNum, sha1))
  def files = filesUrl.getText().substring(5)
  def filesJson = new JsonSlurper().parseText(files)
  filesJson.keySet().findAll { it != "/COMMIT_MSG" }
}

def buildsForMode(refspec,sha1,changeUrl,mode,tools,targetBranch,retryTimes,codestyle) {
    def builds = []
    if(codestyle) {
      builds += {
        Globals.buildsList.put("codestyle", build("Gerrit-codestyle",
                               REFSPEC: refspec, BRANCH: sha1, CHANGE_URL: changeUrl, MODE: mode,
                               TARGET_BRANCH: targetBranch))
                     println "Builds status:"
                     Globals.buildsList.each {
                       n, v -> println "  $n : ${v.getResult()}\n    (${v.getBuildUrl() + "consoleText"})"
                     }
      }
    }

    for (tool in tools) {
      def buildName = "Gerrit-verifier-$tool"
      def key = "$tool/$mode"
      builds += {
                   retry (retryTimes) {
                     Globals.buildsList.put(key,
                       build(buildName, REFSPEC: refspec, BRANCH: sha1,
                             CHANGE_URL: changeUrl, MODE: mode, TARGET_BRANCH: targetBranch))
                     println "Builds status:"
                     Globals.buildsList.each {
                       n, v -> println "  $n : ${v.getResult()}\n    (${v.getBuildUrl() + "consoleText"})"
                     }
                   }
                }
    }
    return builds
}

def sh(cwd, command) {
    def sout = new StringBuilder(), serr = new StringBuilder()
    println "SH: $command"
    def shell = command.execute([],cwd)
    shell.consumeProcessOutput(sout, serr)
    shell.waitForOrKill(30000)
    println "OUT: $sout"
    println "ERR: $serr"
}

def buildChange(change) {
  def sha1 = change.current_revision
  def changeNum = change._number
  def revision = change.revisions.get(sha1)
  def ref = revision.ref
  def patchNum = revision._number
  def branch = change.branch
  def changeUrl = Globals.gerrit + "#/c/" + changeNum + "/" + patchNum
  def refspec = "+" + ref + ":" + ref.replaceAll('ref/', 'ref/remotes/origin/')
  def tools = []
  def modes = ["reviewdb"]
  def workspace = build.environment.get("WORKSPACE")
  println "workspace: $workspace"
  def cwd = new File("$workspace")
  println "cwd: $cwd"
  println "ref: $ref"

  sh(cwd, "git fetch origin $ref")
  sh(cwd, "git checkout FETCH_HEAD")
  sh(cwd, "git fetch origin $branch")
  sh(cwd, 'git config user.name "Jenkins Build"')
  sh(cwd, 'git config user.email "jenkins@gerritforge.com"')
  sh(cwd, 'git merge --no-commit --no-edit --no-ff FETCH_HEAD')

  if(new java.io.File("$cwd/BUCK").exists()) {
    tools += ["buck"]
  } else if(new java.io.File("$cwd/BUILD").exists()) {
    tools += ["bazel"]
  }

  println "Building Change " + changeUrl
  build.setDescription("""<a href='$changeUrl' target='_blank'>Change #$changeNum</a>""")

  if(branch == "master" || branch == "stable-2.15") {
    modes += "notedb"
  }

  if(branch == "master" || branch == "stable-2.15" || branch == "stable-2.14") {
    def changedFiles = getChangedFiles(changeNum, sha1)
    def polygerritFiles = changedFiles.findAll { it.startsWith("polygerrit-ui") }

    if(polygerritFiles.size() > 0) {
      if(changedFiles.size() == polygerritFiles.size()) {
        println "Only PolyGerrit UI changes detected, skipping other test modes..."
        modes = ["polygerrit"]
      } else {
        println "PolyGerrit UI changes detected, adding 'polygerrit' validation..."
        modes += "polygerrit"
      }
    }
  }

  def builds = []
  println "Running validation jobs using $tools builds for $modes ..."
  modes.collect {
    buildsForMode(refspec,sha1,changeUrl,it,tools,branch,1,Globals.codeStyleBranches.contains(branch))
  }.each { builds += it }

  def buildsWithResults = parallelBuilds(builds)
  def codestyleResult = buildsWithResults.find{ it[0] == "codestyle" }
  if(codestyleResult) {
    def resCodeStyle = getVerified(1, codestyleResult[1])
    def codestyleBuild = Globals.buildsList["codestyle"]
    gerritLabelCodestyle(change, sha1, resCodeStyle, findCodestyleFilesInLog(codestyleBuild), codestyleBuild)
  }

  flaky = flakyBuilds(buildsWithResults.findAll { it[0] != "codestyle" })
  if(flaky.size > 0) {
    println "** FLAKY Builds detected: ${flaky}"

    def retryBuilds = []
    def toolsAndModes = flaky.collect { it.split("/") }

    toolsAndModes.each {
      def tool = it[0]
      def mode = it[1]
      Globals.buildsList.remove(it)
      retryBuilds += buildsForMode(refspec,sha1,changeUrl,mode,[tool],branch,3,false)
    }
    buildsWithResults = parallelBuilds(retryBuilds)
  }

  def resVerify = buildsWithResults.findAll{ it != codestyleResult }.inject(1) { acc, buildResult -> getVerified(acc, buildResult[1]) }

  def resAll = codestyleResult ? getVerified(resVerify, codestyleResult[1]) : resVerify

  gerritLabelVerify(change, sha1, resVerify, Globals.buildsList.findAll { key,build -> key != "codestyle" })

  switch(resAll) {
    case 0: build.state.result = ABORTED
            break
    case 1: build.state.result = SUCCESS
            break
    case -1: build.state.result = FAILURE
             break
  }
}

def parallelBuilds(builds) {
  ignore(FAILURE) {
    parallel (builds)
  }
  def results = Globals.buildsList.values().collect { waitForResult(it) }
  def buildsWithResults = []

  Globals.buildsList.keySet().eachWithIndex {
    key,index -> buildsWithResults.add(new Tuple(key, results[index]))
  }
  return buildsWithResults
}

def flakyBuilds(buildsWithResults) {
  def flaky = buildsWithResults.findAll { it[1] == null || it[1] != SUCCESS }
  if(flaky.size == buildsWithResults.size) {
    return []
  }

  return flaky.collect { it[0] }
}

def requestedChangeId = params.get("CHANGE_ID")

queryUrl =
  new URL("${Globals.gerrit}changes/$requestedChangeId/?pp=0&O=3")

def change = queryUrl.getText().substring(5)
def jsonSlurper = new JsonSlurper()
def changeJson = jsonSlurper.parseText(change)

sha1 = changeJson.current_revision
if(sha1 == null) {
  println "[WARNING] Skipping change " + changeJson.change_id + " because it does not have any current revision or patch-set"
} else {
  buildChange(changeJson)
}


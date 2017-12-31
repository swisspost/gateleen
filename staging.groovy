import groovy.json.JsonSlurper

class Staging {
    int delayBetweenRetries = 30
    int numberOfRetries = 20
    String ossUserName
    String ossPassword

    Staging(String userName, String password) {
        ossUserName = userName
        ossPassword = password
    }

    static void main(String[] args) {
        def stagingHelper = new Staging(System.getenv("CI_DEPLOY_USERNAME"), System.getenv("CI_DEPLOY_PASSWORD"))
        stagingHelper.run(args[0])
    }

    private void run(String cmd) {
        switch (cmd) {
            case "close":
                println "trying to close nexus repository ..."
                doWithRetry(this.&close)
                println " > done"
                break
            case "drop":
                println "trying to drop nexus repository ..."
                try {
                    doWithRetry(this.&drop)
                } catch (Exception e) {
                    println "No repository to drop found? " + e
                }
                println " > done"
                break
            case "promote":
                println "trying to promote nexus repository ..."
                doWithRetry(this.&promote)
                println " > done"
                break
        }
    }

    int doWithRetry(Closure operation) {
        int counter = 0
        int numberOfAttempts = Integer.valueOf(numberOfRetries)
        while (true) {
            try {
                println "Attempt $counter/$numberOfAttempts..."
                if (operation() == 0) {
                    return 0
                }
            } catch (Exception e) {
                if (counter >= numberOfAttempts) {
                    println "Giving up."
                    throw e
                } else {
                    waitBeforeNextAttempt()
                }
            } finally {
                counter++
            }
        }
    }

    void waitBeforeNextAttempt() {
        sleep(Integer.valueOf(delayBetweenRetries))
    }

    int drop() {
        def stagingProfileId = getStagingProfileId()
        def repositoryId = getRepositoryId(stagingProfileId, "released")
        if (repositoryId == null) {
            println("No more action.")
            return 0
        }
        def data = getData(stagingProfileId, repositoryId)

        // drop repository
        def response = ['bash', '-c', "curl -sL -w \"%{http_code}\" -H \"Content-Type: application/json\" -X POST -d '" + data + "' https://" + ossUserName + ":" + ossPassword + "@oss.sonatype.org/service/local/staging/profiles/" + stagingProfileId + "/drop -o /dev/null"].execute().text
        println response

        if (Integer.valueOf(response) > 299) {
            throw new IllegalArgumentException("HTTP request failed, getting status code: ${response}")
        }
        return Integer.valueOf(response)
    }


    int close() {
        def stagingProfileId = getStagingProfileId()
        def repositoryId = getRepositoryId(stagingProfileId, "open")
        if (repositoryId == null) {
            println("No more action.")
            return 0
        }
        def data = getData(stagingProfileId, repositoryId)

        // close repository
        def response = ['bash', '-c', "curl -sL -w \"%{http_code}\" -H \"Content-Type: application/json\" -X POST -d '" + data + "' https://" + ossUserName + ":" + ossPassword + "@oss.sonatype.org/service/local/staging/profiles/" + stagingProfileId + "/finish -o /dev/null"].execute().text
        println response

        if (Integer.valueOf(response) > 299) {
            throw new IllegalArgumentException("HTTP request failed, getting status code: ${response}")
        }
        return Integer.valueOf(response)
    }

    int promote() {
        def stagingProfileId = getStagingProfileId()
        def repositoryId = getRepositoryId(stagingProfileId, "closed")
        if (repositoryId == null) {
            println("No more action.")
            return 0
        }
        def data = getData(stagingProfileId, repositoryId)

        // promote repository
        def response = ['bash', '-c', "curl -sL -w \"%{http_code}\" -H \"Content-Type: application/json\" -X POST -d '" + data + "' https://" + ossUserName + ":" + ossPassword + "@oss.sonatype.org/service/local/staging/profiles/" + stagingProfileId + "/promote -o /dev/null"].execute().text
        if (Integer.valueOf(response) > 299) {
            throw new IllegalArgumentException("HTTP request failed, getting status code: ${response}")
        }
        return Integer.valueOf(response)
    }

    String getStagingProfileId() {
        def response = ['bash', '-c', "curl -s -H \"Accept: application/json\" -X GET https://" + ossUserName + ":" + ossPassword + "@oss.sonatype.org/service/local/staging/profiles"].execute().text

        def json = new JsonSlurper().parseText(response)
        def profileList = json.data
        Integer found = 0
        String stagingProfileId = ""
        for (int index = 0; index < profileList.size(); index++) {
            if (profileList[index].name.equals("org.swisspush")) {
                found++;
                stagingProfileId = profileList[index].id
            }
        }

        if (found == 0) {
            throw new IllegalArgumentException("No stagingProfileId found!")
        } else if (found > 1) {
            throw new IllegalArgumentException("Multiple stagingProfileId's found!")
        }

        println "Found stagingProfileId: " + stagingProfileId

        return stagingProfileId
    }

    def getRepositoryId(String stagingProfileId, String state) {
        def response = ['bash', '-c', "curl -s -H \"Accept: application/json\" -X GET https://" + ossUserName + ":" + ossPassword + "@oss.sonatype.org/service/local/staging/profile_repositories/" + stagingProfileId].execute().text
        def json = new JsonSlurper().parseText(response)
        println(response)
        if (json.data.size() == 0) {
            println("No staging repository found!")
            return null
        } else if (json.data.size() > 1) {
            throw new IllegalArgumentException("Multiple staging repositories found!")
        }

        def repository = json.data[0]

        // check state - close
        if (state == "open" && repository.type != state) {
            println("No open repository found!")
            return null
        }

        if (state == "closed" && repository.type != state) {
            println("No closed repository found!")
            return null
        }

        println "Found repositoryId: " + repository.repositoryId

        return repository.repositoryId
    }

    static String getData(String stagingProfileId, String repositoryId) {
        return "{\"data\" : {\"stagedRepositoryId\" : " + repositoryId + ",\"description\" : \"Automatically released/promoted with Travis\",  \"targetRepositoryId\" : " + stagingProfileId + " }}"
    }

}
FROM gerritforge/gerrit-ci-slave-chrome:jessie-69.0.3497.92

# Node JS for PolyGerrit build

RUN curl -sL https://deb.nodesource.com/setup_$NODE_VER.x | bash -
RUN apt-get update && apt-get install -y nodejs gcc g++ make && \
    curl -sL https://dl.yarnpkg.com/debian/pubkey.gpg | apt-key add - && \
    echo "deb https://dl.yarnpkg.com/debian/ stable main" | tee /etc/apt/sources.list.d/yarn.list && \
    apt-get update && apt-get install yarn

COPY wct.conf.json /home/jenkins/
RUN chown jenkins:jenkins /home/jenkins/wct.conf.json

RUN npm install --unsafe-perm -g eslint@"=4.19.0" eslint-config-google eslint-plugin-html typescript fried-twinkie@">0.2.0" polylint polymer-cli
RUN npm install --unsafe-perm -g 'web-component-tester@$WCT_VER'

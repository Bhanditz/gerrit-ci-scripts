SLAVE_DIRS ?= slave-debian slave-chrome slave-node-wct slave-buck slave-mvn slave-bazel-nocache slave-bazel slave-sbt slave-release
ALL_DIRS ?= master $(SLAVE_DIRS)
NO_CACHE ?= true

default: build

build clean publish:
	for dir in $(ALL_DIRS); do (make NO_CACHE=$(NO_CACHE) -C $$dir $@) || exit 1; done

build_slave:
	for dir in $(SLAVE_DIRS); do (make NO_CACHE=$(NO_CACHE) -C $$dir build) || exit 1; done

start stop restart:
	make -C master $@


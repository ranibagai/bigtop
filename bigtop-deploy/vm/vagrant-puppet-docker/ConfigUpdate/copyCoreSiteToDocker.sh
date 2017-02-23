## ARGUMENT 1: IF DOCKER CONTAINER PROVISIONING IS NOT DONE YET, ISDEPLOY=TRUE, ELSE FALSE
## ARGUMENT 2: DOCKER CONTAINER ID
## ARGUMENT 3: CORE-SITE.XML FILE TO COPY
## ARGUMENT 4: CORE-SITE.XML COPY DESTINATION. IF DEPLOY, TO BIGTOP FOLDER. ELSE CONF FOLDER.
ISDEPLOY=$1
DOCKER_CONTAINER_ID=$2
CORE_SITE_FILE_TO_COPY=$3
CORE_SITE_DESTINATION_FOLDER=${4-"/etc/hadoop/conf/"}

if [ "$ISDEPLOY" = "true" ]; then
CORE_SITE_DESTINATION_FOLDER="/bigtop-home/bigtop-deploy/puppet/modules/hadoop/templates/"
fi

sudo docker cp $CORE_SITE_FILE_TO_COPY $DOCKER_CONTAINER_ID:$CORE_SITE_DESTINATION_FOLDER



#!/usr/bin/ksh
##################################################################################
# FileName: voliconClient.sh
#         : invokes volicon client, continous running process to pull volicon
#           data from volicon WS via WS (REST)
#         : Manoj
##################################################################################
ScriptName=${0##*/}
class_name="volicon.rpmservicevisits.RPMServiceVisits"

printUsage()
{
  echo " "
  echo "Description"
  echo "  This script is used to start/stop/check the delphi Client process"
  echo " "
  echo "Usage"
  echo "  ${ScriptName} [-start|-stop|-check]"
  echo " "
  exit -1
}

#. ~/.bash_profile
if [ -z "$NPI_DIR" ]
then
  echo "Error: NPI_DIR not set"
  exit 1
fi

. $NPI_DIR/config/npibatch.env

export CLASSPATH=$NPI_DIR/lib/volicon.jar:$NPI_DIR/lib/log4j-1.2.15.jar:$CLASSPATH


icount=$(ps -ef | grep $class_name | grep -v grep | wc -l)

##----- Process command line
ACTION="-check"
OPTIONS=""
while [[ $1 != "" ]]
do
  case $1 in
    -start|-stop|-check)
      ACTION="$1"
      OPTIONS="$OPTIONS $1"
      ;;
    *)
      echo "Invalid option: \"$1\""
      printUsage
      ;;
  esac
  shift
done

if [[ "$ACTION" = "-stop" ]]
then
  if [[ $icount -lt 1 ]]
  then
    echo "********* volicon client process is NOT RUNNING *******"
    exit 1
  fi

  pkill -f $class_name
  sleep 1
  icount=$(ps -ef | grep $class_name | grep -v grep | wc -l)

  if [[ $icount -gt 0 ]]
  then
    echo "*************volicon client process Could not be stopped **********"
    echo "__________________________________________________________"
    ps -ef | grep $class_name | grep -v grep
    echo "__________________________________________________________"
  else
    echo "************* volicon Client process stopped **********************"
  fi


elif [[ "$ACTION" = "-start" ]]
then
  if [[ $icount -gt 0 ]]
  then
    echo ""
    echo "********* volicon client process is ALREADY RUNNING *******"
    echo "__________________________________________________________"
    ps -ef | grep $class_name | grep -v grep
    echo "__________________________________________________________"
    exit 1
  fi

  nohup java -cp $CLASSPATH $class_name -config $NPI_DIR/config/volicon.properties > $NPI_DIR/logs/voliconClient/voliconClient_sh.log 2>&1 &

  sleep 1

  cat $NPI_DIR/logs/voliconClient/voliconClient_sh.log

  icount=$(ps -ef | grep $class_name | grep -v grep | wc -l)

  echo "__________________________________________________________"
  ps -ef | grep $class_name | grep -v grep
  echo "__________________________________________________________"

else
  if [[ $icount -gt 0 ]]
  then
    echo ""
    echo "********* volicon client process is RUNNING *******"
    echo "__________________________________________________________"
    ps -ef | grep $class_name | grep -v grep
    echo "__________________________________________________________"
  else
    echo ""
    echo "********* volicon client process is NOT running *******"
    echo ""
  fi

fi


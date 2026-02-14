#!/bin/bash

# Yoot Email API Service Control Script
SERVICE_NAME="yoot-email-api"
JAR_NAME="email-utilities-0.0.1-SNAPSHOT.jar"

case "$1" in
    start)
        echo "Starting $SERVICE_NAME service..."
        sudo systemctl start $SERVICE_NAME
        sudo systemctl status $SERVICE_NAME --no-pager
        ;;
    stop)
        echo "Stopping $SERVICE_NAME service..."
        sudo systemctl stop $SERVICE_NAME
        sudo systemctl status $SERVICE_NAME --no-pager
        ;;
    restart)
        echo "Restarting $SERVICE_NAME service..."
        sudo systemctl restart $SERVICE_NAME
        sudo systemctl status $SERVICE_NAME --no-pager
        ;;
    status)
        sudo systemctl status $SERVICE_NAME --no-pager
        ;;
    logs)
        echo "Showing logs for $SERVICE_NAME service..."
        sudo journalctl -u $SERVICE_NAME -f
        ;;
    logs-recent)
        echo "Showing recent logs for $SERVICE_NAME service..."
        sudo journalctl -u $SERVICE_NAME --since "1 hour ago" --no-pager
        ;;
    enable)
        echo "Enabling $SERVICE_NAME service to start on boot..."
        sudo systemctl enable $SERVICE_NAME
        ;;
    disable)
        echo "Disabling $SERVICE_NAME service from starting on boot..."
        sudo systemctl disable $SERVICE_NAME
        ;;
    reload-config)
        echo "Reloading systemd configuration..."
        sudo systemctl daemon-reload
        ;;
    build)
        echo "Building application..."
        ./gradlew clean bootJar -x test
        if [ $? -eq 0 ]; then
            cp build/libs/$JAR_NAME .
            echo "Build successful. JAR copied to current directory."
        else
            echo "Build failed."
            exit 1
        fi
        ;;
    build-and-restart)
        echo "Building application and restarting service..."
        ./gradlew clean bootJar -x test
        if [ $? -eq 0 ]; then
            cp build/libs/$JAR_NAME .
            sudo systemctl restart $SERVICE_NAME
            sleep 3
            sudo systemctl status $SERVICE_NAME --no-pager
        else
            echo "Build failed, not restarting service."
            exit 1
        fi
        ;;
    *)
        echo "Usage: $0 {start|stop|restart|status|logs|logs-recent|enable|disable|reload-config|build|build-and-restart}"
        echo
        echo "Commands:"
        echo "  start             - Start the service"
        echo "  stop              - Stop the service"
        echo "  restart           - Restart the service"
        echo "  status            - Show service status"
        echo "  logs              - Show live logs"
        echo "  logs-recent       - Show recent logs"
        echo "  enable            - Enable service to start on boot"
        echo "  disable           - Disable service from starting on boot"
        echo "  reload-config     - Reload systemd configuration"
        echo "  build             - Build JAR only (no restart)"
        echo "  build-and-restart - Build app and restart service"
        exit 1
        ;;
esac

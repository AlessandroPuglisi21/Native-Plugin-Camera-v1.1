var exec = require('cordova/exec');

var UsbCamera = {
    open: function(options, onFrame, onError) {
        options = options || {};
        
        var success = function(result) {
            if (typeof onFrame === 'function') {
                onFrame(result);
            }
        };
        
        var error = function(err) {
            if (typeof onError === 'function') {
                onError(err);
            }
        };
        
        exec(success, error, 'UsbExternalCamera', 'open', [options]);
    },
    
    stopPreview: function(callback, errorCallback) {
        exec(callback, errorCallback, 'UsbExternalCamera', 'stopPreview', []);
    },
    
    takePhoto: function(callback, errorCallback) {
        exec(callback, errorCallback, 'UsbExternalCamera', 'takePhoto', []);
    },
    
    close: function(callback, errorCallback) {
        exec(callback, errorCallback, 'UsbExternalCamera', 'close', []);
    },
    
    listCameras: function (callback, errorCallback) {
        exec(callback, errorCallback, 'UsbExternalCamera', 'listCameras', []);
    },
    
    disableAutofocus: function (callback, errorCallback) {
        exec(callback, errorCallback, 'UsbExternalCamera', 'disableAutofocus', []);
    },
    
    initSimple: function(successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'UsbExternalCamera', 'initSimple', []);
    },
    
    triggerAutofocus: function(successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'UsbExternalCamera', 'triggerAutofocus', []);
    },
    
    optimizeAutofocusForUsb: function(successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'UsbExternalCamera', 'optimizeAutofocusForUsb', []);
    }
};

module.exports = UsbCamera;
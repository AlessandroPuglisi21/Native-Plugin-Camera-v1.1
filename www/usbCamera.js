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
    },
    
    setFocusDistance: function(distance, successCallback, errorCallback) {
        if (typeof distance !== 'number' || distance < 0 || distance > 1) {
            if (typeof errorCallback === 'function') {
                errorCallback('Focus distance must be a number between 0.0 (infinity) and 1.0 (minimum distance)');
            }
            return;
        }
        
        cordova.exec(successCallback, errorCallback, 'UsbExternalCamera', 'setFocusDistance', [distance]);
    },
    
    setUvcAutoFocus: function(enable, successCallback, errorCallback) {
        if (typeof enable !== 'boolean') {
            if (typeof errorCallback === 'function') {
                errorCallback('Enable parameter must be a boolean (true or false)');
            }
            return;
        }
        
        cordova.exec(successCallback, errorCallback, 'UsbExternalCamera', 'setUvcAutoFocus', [enable]);
    },
    
    setUvcFocusAbsolute: function(value, successCallback, errorCallback) {
        if (typeof value !== 'number' || value < 0 || value > 1) {
            if (typeof errorCallback === 'function') {
                errorCallback('Focus value must be a number between 0.0 and 1.0');
            }
            return;
        }
        
        cordova.exec(successCallback, errorCallback, 'UsbExternalCamera', 'setUvcFocusAbsolute', [value]);
    },
    
    setUvcAutoExposure: function(enable, successCallback, errorCallback) {
        if (typeof enable !== 'boolean') {
            if (typeof errorCallback === 'function') {
                errorCallback('Enable parameter must be a boolean (true or false)');
            }
            return;
        }
        cordova.exec(successCallback, errorCallback, 'UsbExternalCamera', 'setUvcAutoExposure', [enable]);
    },
    
    setUvcExposureAbsolute: function(value, successCallback, errorCallback) {
        if (typeof value !== 'number' || value < 0 || value > 1) {
            if (typeof errorCallback === 'function') {
                errorCallback('Exposure value must be a number between 0.0 and 1.0');
            }
            return;
        }
        cordova.exec(successCallback, errorCallback, 'UsbExternalCamera', 'setUvcExposureAbsolute', [value]);
    },
    
    debugUvcExposure: function(successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'UsbExternalCamera', 'debugUvcExposure', []);
    },
    
    setUvcBrightness: function(value, successCallback, errorCallback) {
        if (typeof value !== 'number' || value < 0 || value > 1) {
            if (typeof errorCallback === 'function') {
                errorCallback('Brightness value must be a number between 0.0 and 1.0');
            }
            return;
        }
        cordova.exec(successCallback, errorCallback, 'UsbExternalCamera', 'setUvcBrightness', [value]);
    },
    
    debugUvcBrightness: function(successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'UsbExternalCamera', 'debugUvcBrightness', []);
    },
    
    debugUvcControls: function(successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'UsbExternalCamera', 'debugUvcControls', []);
    },
    
    recoverCamera: function(successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'UsbExternalCamera', 'recoverCamera', []);
    }
};

module.exports = UsbCamera;
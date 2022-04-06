// Karma configuration
// Generated on Thu Apr 30 2015 10:15:46 GMT+0200 (CEST)

module.exports = function (config) {
    'use strict';
    config.set({

        // base path that will be used to resolve all patterns (eg. files, exclude)
        basePath: '../../',

        // frameworks to use
        // available frameworks: https://npmjs.org/browse/keyword/karma-adapter
        frameworks: ['jasmine'],


        // list of files / patterns to load in the browser
        files: [
            'target/webjars/META-INF/resources/webjars/angularjs/1.5.8/angular.min.js',
            'target/webjars/META-INF/resources/webjars/angularjs/1.5.8/angular-mocks.js',

            // vertexbus and socketjs
            'target/dist/js/sockjs.js',
            'target/dist/js/vertxbus.js',

            // hook js
            'target/dist/js/gateleen-hook.js',

            // the test files
            'src/test/**/*.js'
        ],


        // list of files to exclude
        exclude: [],

        // we use ngHtml2Js preprocessor for unit testing directives with external templates

        //https://github.com/karma-runner/karma-ng-html2js-preprocessor
        ngHtml2JsPreprocessor: {
            // strip off the main app path as we are in test env
            // stripPrefix: 'modules/',
            prependPrefix: 'gateleen-hook-js/',
            // stripSuffix: '.html',
            // or define a custom transform function
            /*cacheIdFromPath: function(filepath) {
                return cacheId;
            },*/
            moduleName: 'gateleen-hook-js.karma.prefill-template-cache'
        },
        // test results reporter to use
        // possible values: 'dots', 'progress'
        // available reporters: https://npmjs.org/browse/keyword/karma-reporter
        reporters: ['progress', 'coverage', 'junit', 'html'],

        coverageReporter: {
            reporters: [
                {type: 'html', subdir: 'cov-report-html'},
                {type: 'lcov', subdir: 'cov-report-lcov'}
            ],
            dir: 'reports',
            subdir: 'coverage'
        },

        htmlReporter: {
            outputDir: 'reports/', // where to put the reports
            templatePath: null, // set if you moved jasmine_template.html
            focusOnFailures: true, // reports show failures on start
            namedFiles: false, // name files instead of creating sub-directories
            pageTitle: null, // page title for reports; browser info by default
            urlFriendlyName: false, // simply replaces spaces with _ for files/dirs
            reportName: 'test-html-report' // report summary filename; browser info by default
        },

        junitReporter: {
            outputFile: 'reports/junit/TEST-xunit.xml',
            useBrowserName: false // add browser name to report and classes names
        },

        // enable / disable colors in the output (reporters and logs)
        colors: true,

        // level of logging
        // possible values: config.LOG_DISABLE || config.LOG_ERROR || config.LOG_WARN || config.LOG_INFO || config.LOG_DEBUG
        logLevel: config.LOG_INFO,


        // enable / disable watching file and executing tests whenever any file changes
        autoWatch: false,

        // start these browsers
        // available browser launchers: https://npmjs.org/browse/keyword/karma-launcher
        // browsers: ['Chrome'],
        browsers: ['ChromeHeadless'],
        // Continuous Integration mode
        // if true, Karma captures browsers, runs the tests and exits
        singleRun: true
    });
};

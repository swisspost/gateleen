module.exports = function (grunt) {
    'use strict';
    grunt.initConfig({

        pkg: grunt.file.readJSON('package.json'),
        clean: ['dist/'],
        karma: {
            unit: {
                configFile: 'src/test/karma.conf.js'
            }
        },
        watch: {
            karma: {
                files: ['src/test/**/*.js', '**/*.js'],
                tasks: ['karma:unit']
            }
        },
        jshint: {
            options: {
                jshintrc: '.jshintrc'
            },
            all: [
                'Gruntfile.js',
                'test/javascript/**/*.js'
            ]
        }
    });

    // load grunt modules
    grunt.loadNpmTasks('grunt-karma');
    grunt.loadNpmTasks('grunt-contrib-clean');
    grunt.loadNpmTasks('grunt-contrib-watch');
    grunt.loadNpmTasks('grunt-contrib-jshint');

    // register tasks
    grunt.registerTask('test', [
        'karma'
    ]);


    grunt.registerTask('build', [
        //'test'
    ]);

    // watch will sass + unit tests
    grunt.registerTask('dev', ['watch']);
};

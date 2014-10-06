/*global define,App*/
define([
    'common-objects/common/singleton_decorator',
    'common-objects/views/base',
    'views/workflows/workflow_content_list',
    'text!templates/nav/workflow_nav.html'
], function (singletonDecorator, BaseView, WorkflowContentListView, template) {
	'use strict';
    var WorkflowNavView = BaseView.extend({
        el: '#workflow-nav',
	    template: template,

        initialize: function () {
            BaseView.prototype.initialize.apply(this, arguments);
            this.render();
        },
        setActive: function () {
            if (App.$changeManagementMenu) {
                App.$changeManagementMenu.find('.active').removeClass('active');
            }
            this.$el.find('.nav-list-entry').first().addClass('active');
        },
        showContent: function () {
            this.setActive();
            this.addSubView(
                new WorkflowContentListView()
            ).render();
        }
    });
    WorkflowNavView = singletonDecorator(WorkflowNavView);
    return WorkflowNavView;
});
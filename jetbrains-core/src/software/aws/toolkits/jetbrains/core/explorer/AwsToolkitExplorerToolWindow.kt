// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.core.explorer

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.components.BorderLayoutPanel
import software.aws.toolkits.jetbrains.core.credentials.CredsComboBoxActionGroup
import software.aws.toolkits.jetbrains.core.explorer.devToolsTab.DevToolsToolWindow
import software.aws.toolkits.resources.message
import java.awt.Component

class AwsToolkitExplorerToolWindow(private val project: Project) : SimpleToolWindowPanel(true, true) {
    private val tabPane = JBTabbedPane()

    private val tabComponents = mapOf<String, () -> Component>(
        DEVTOOLS_TAB_ID to { DevToolsToolWindow.getInstance(project) },
        EXPLORER_TAB_ID to { ExplorerToolWindow.getInstance(project) }
    )

    init {
        runInEdt {
            val content = BorderLayoutPanel()
            setContent(content)
            val group = CredsComboBoxActionGroup(project)

            toolbar = BorderLayoutPanel().apply {
                addToCenter(
                    ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, group, true).apply {
                        layoutPolicy = ActionToolbar.AUTO_LAYOUT_POLICY
                        setTargetComponent(this@AwsToolkitExplorerToolWindow)
                    }.component
                )

                val actionManager = ActionManager.getInstance()
                val rightActionGroup = DefaultActionGroup(
                    actionManager.getAction("aws.toolkit.toolwindow.credentials.rightGroup.more"),
                    actionManager.getAction("aws.toolkit.toolwindow.credentials.rightGroup.help")
                )

                addToRight(
                    ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, rightActionGroup, true).apply {
                        // revisit if these actions need the tool window as a data provider
                        setTargetComponent(component)
                    }.component
                )
            }

            // main content
            tabComponents.forEach { name, contentProvider ->
                tabPane.addTab(name, contentProvider())
            }
            content.addToCenter(tabPane)

            val toolkitToolWindowListener = ToolkitToolWindowListener(project)
            val onTabChange = {
                toolkitToolWindowListener.tabChanged(tabPane.getTitleAt(tabPane.selectedIndex))
            }
            tabPane.model.addChangeListener {
                onTabChange()
            }
            onTabChange()
        }
    }

    fun selectTab(tabName: String): Component? {
        val index = tabPane.indexOfTab(tabName)
        if (index == -1) {
            return null
        }

        val component = tabPane.getComponentAt(index)
        if (component != null) {
            tabPane.selectedComponent = tabPane.getComponentAt(index)

            return component
        }

        return null
    }

    fun getTabLabelComponent(tabName: String): Component? {
        val index = tabPane.indexOfTab(tabName)
        if (index == -1) {
            return null
        }

        return tabPane.getTabComponentAt(index)
    }

    companion object {
        val EXPLORER_TAB_ID = message("explorer.toolwindow.title")
        val DEVTOOLS_TAB_ID = message("aws.developer.tools.tab.title")

        fun getInstance(project: Project) = project.service<AwsToolkitExplorerToolWindow>()

        fun toolWindow(project: Project) = ToolWindowManager.getInstance(project).getToolWindow(AwsToolkitExplorerFactory.TOOLWINDOW_ID)
            ?: error("Can't find AwsToolkitExplorerToolWindow")
    }
}

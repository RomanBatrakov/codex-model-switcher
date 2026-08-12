package com.github.romanbatrakov.codexmodelswitcher;

import com.intellij.ide.DataManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectUtil;

import javax.swing.JComponent;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.KeyEventDispatcher;
import java.awt.KeyEventPostProcessor;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Installs a global AWT key hook so Ctrl+&lt;digit&gt; switches the matching
 * model preset from anywhere in the IDE, including inside the terminal,
 * where a regular IntelliJ action shortcut would not otherwise fire.
 */
@Service(Service.Level.APP)
public final class CodexKeyboardShortcutService implements Disposable {
    private final KeyEventDispatcher dispatcher = this::dispatch;
    private final KeyEventPostProcessor postProcessor = this::postProcess;
    private final Set<Integer> suppressedKeyCodes = ConcurrentHashMap.newKeySet();

    /** Registers the key event hooks with the current {@link KeyboardFocusManager}. */
    public CodexKeyboardShortcutService() {
        KeyboardFocusManager focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        focusManager.addKeyEventDispatcher(dispatcher);
        focusManager.addKeyEventPostProcessor(postProcessor);
    }

    /** Returns the application-level singleton instance. */
    public static CodexKeyboardShortcutService getInstance() {
        return ApplicationManager.getApplication().getService(CodexKeyboardShortcutService.class);
    }

    private boolean dispatch(KeyEvent event) {
        if (event.isConsumed()) {
            return false;
        }
        if (event.getID() == KeyEvent.KEY_TYPED) {
            return suppressTypedDigit(event);
        }
        if (event.getID() == KeyEvent.KEY_RELEASED) {
            return suppressReleasedDigit(event);
        }
        if (event.getID() != KeyEvent.KEY_PRESSED) {
            return false;
        }
        int modifiers = event.getModifiersEx();
        int forbiddenModifiers = InputEvent.SHIFT_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK;
        if ((modifiers & InputEvent.CTRL_DOWN_MASK) == 0 || (modifiers & forbiddenModifiers) != 0) {
            return false;
        }

        ModelPreset preset = presetFor(event.getKeyCode());
        if (preset == null) {
            return false;
        }
        Project project = projectFor(event.getComponent());
        if (project == null || project.isDisposed()) {
            return false;
        }

        suppressedKeyCodes.add(event.getKeyCode());
        event.consume();
        CodexWorkflow.switchModel(project, preset);
        return true;
    }

    private boolean postProcess(KeyEvent event) {
        if (event.isConsumed()) {
            return false;
        }
        if (event.getID() == KeyEvent.KEY_TYPED) {
            return suppressTypedDigit(event);
        }
        if (event.getID() == KeyEvent.KEY_RELEASED) {
            return suppressReleasedDigit(event);
        }
        return false;
    }

    private boolean suppressTypedDigit(KeyEvent event) {
        int keyCode = keyCodeForDigit(event.getKeyChar());
        if (keyCode == 0 || !suppressedKeyCodes.remove(keyCode)) {
            return false;
        }
        event.consume();
        return true;
    }

    private boolean suppressReleasedDigit(KeyEvent event) {
        if (!suppressedKeyCodes.remove(event.getKeyCode())) {
            return false;
        }
        event.consume();
        return true;
    }

    private static ModelPreset presetFor(int keyCode) {
        CodexModelSwitcherSettings settings = CodexModelSwitcherSettings.getInstance();
        for (ModelPreset.PresetId presetId : ModelPreset.PresetId.values()) {
            if (keyCodeForDigit(settings.shortcut(presetId)) == keyCode) {
                return ModelPreset.forId(presetId);
            }
        }
        return null;
    }

    private static int keyCodeForDigit(char keyChar) {
        return Character.isDigit(keyChar) ? KeyEvent.getExtendedKeyCodeForChar(keyChar) : 0;
    }

    private static int keyCodeForDigit(String key) {
        if (key == null || key.length() != 1 || !Character.isDigit(key.charAt(0))) {
            return 0;
        }
        return keyCodeForDigit(key.charAt(0));
    }

    private static Project projectFor(Component component) {
        try {
            Project project = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(component));
            if (project != null) {
                return project;
            }
            if (component instanceof JComponent swingComponent) {
                return ProjectUtil.guessCurrentProject(swingComponent);
            }
        } catch (RuntimeException ignored) {
            // Some transient terminal components do not expose a data context.
        }
        Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
        if (openProjects.length == 1 && !openProjects[0].isDisposed()) {
            return openProjects[0];
        }
        return null;
    }

    /** Unregisters the key event hooks so they don't leak past the plugin/IDE lifecycle. */
    @Override
    public void dispose() {
        KeyboardFocusManager focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        focusManager.removeKeyEventDispatcher(dispatcher);
        focusManager.removeKeyEventPostProcessor(postProcessor);
        suppressedKeyCodes.clear();
    }
}

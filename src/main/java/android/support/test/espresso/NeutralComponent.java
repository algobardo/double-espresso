package android.support.test.espresso;

public abstract class NeutralComponent {
    public abstract void execute();
    public abstract boolean canExecute();

    public String getName() {
        return getClass().getName();
    }
}

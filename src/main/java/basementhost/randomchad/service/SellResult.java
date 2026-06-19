package basementhost.randomchad.service;

public record SellResult(Status status, int amountSold, double money, double fee) {
    public enum Status {
        SOLD,
        NOTHING_TO_SELL,
        VAULT_FAILED
    }

    public static SellResult sold(int amountSold, double money, double fee) {
        return new SellResult(Status.SOLD, amountSold, money, fee);
    }

    public static SellResult nothingToSell() {
        return new SellResult(Status.NOTHING_TO_SELL, 0, 0D,0);
    }

    public static SellResult vaultFailed() {
        return new SellResult(Status.VAULT_FAILED, 0, 0D,0);
    }
}

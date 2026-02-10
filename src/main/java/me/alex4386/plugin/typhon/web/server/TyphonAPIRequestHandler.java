package me.alex4386.plugin.typhon.web.server;

@FunctionalInterface
public interface TyphonAPIRequestHandler {
    TyphonAPIResponse handle(TyphonAPIRequest request);
}

export const environment = {
  env: 'environment',
  serverUrl: window['env' as keyof Object]['serverUrl' as keyof Object] || '',
};
